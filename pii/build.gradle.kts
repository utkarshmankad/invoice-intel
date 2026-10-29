import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Duration

plugins {
    id("invoiceintel.mutation-testing")
}

dependencies {
    api(project(":common"))
    // Detectors read the Text stage's TextLayer (architecture.md §4.1, §6.3).
    api(project(":text"))
    // The PII stage stores spans, vault entries and redacted blobs; only pii touches the vault
    // tables (architecture.md §4.1, §7, M2.10b). Text already brings both as api.
    api(project(":storage"))
    api(libs.spring.jdbc)
    // HMAC tokens and the AEAD vault (PII-3, PII-7, architecture.md §6.3, ADR-06).
    implementation(libs.tink)
    // NER layer (PII-2(b), architecture.md §6.3, ADR-15): ONNX Runtime and the tokenizers
    // native library come from these jars; nothing is downloaded at run time.
    implementation(libs.djl.api)
    implementation(libs.djl.onnxruntime.engine)
    implementation(libs.djl.tokenizers)
    // Reads the model's config.json (id2label).
    implementation(libs.jackson.databind)
    // The redacted PDF is rebuilt from masked page images (PII-4, ADR-05).
    implementation(libs.pdfbox)

    testImplementation(testFixtures(project(":common")))
}

/**
 * Downloads files of one Hugging Face model repo at a pinned commit and checks each file's
 * SHA-256 (ADR-15). Files are cached outside the repo; a file already there with the right hash
 * is not downloaded again, and a hash mismatch fails the build.
 */
abstract class HuggingFaceDownload : DefaultTask() {
    @get:Input abstract val repo: Property<String>
    @get:Input abstract val revision: Property<String>
    /** Repo-relative path → expected SHA-256 (hex). */
    @get:Input abstract val files: MapProperty<String, String>
    @get:OutputDirectory abstract val targetDir: DirectoryProperty

    @TaskAction
    fun download() {
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build()
        val dir = targetDir.get().asFile.toPath()
        for ((path, expected) in files.get()) {
            val target = dir.resolve(path)
            if (Files.isRegularFile(target) && sha256(target) == expected) {
                continue
            }
            Files.createDirectories(target.parent)
            val tmp = Files.createTempFile(target.parent, ".download", ".part")
            try {
                val uri = URI.create(
                    "https://huggingface.co/${repo.get()}/resolve/${revision.get()}/$path")
                logger.lifecycle("Downloading $uri")
                val request = HttpRequest.newBuilder(uri)
                    .header("User-Agent", "invoice-intel-build")
                    .timeout(Duration.ofMinutes(10))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp))
                check(response.statusCode() == 200) { "HTTP ${response.statusCode()} for $uri" }
                val actual = sha256(tmp)
                check(actual == expected) {
                    "SHA-256 mismatch for ${repo.get()}/$path: expected $expected, got $actual"
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(Files.newInputStream(file), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

// NER model pins (ADR-15). Each repo's files at a full commit SHA, with SHA-256 checked on
// download. `chosenNerModel` is the one the detector and the default test run use
// (`downloadNerModel`); the other candidate is downloaded only by `nerBenchmark`.
data class NerModelPin(val name: String, val repo: String, val revision: String, val files: Map<String, String>)

val nerModelPins = listOf(
    NerModelPin(
        "distilbert-multilingual",
        "Xenova/distilbert-base-multilingual-cased-ner-hrl",
        "c2a4dbf593c57f47004c5bc2d3770d311aee9c43",
        mapOf(
            "README.md" to "70b57a1c647bb1479d14529082b8dd8fae4787c3eb4712b0b49e5dc7757a4c0f",
            "config.json" to "38847be4dc6699b1218a749ed69f888c2ccc7b4deba98e3c4a1cac8cb34d54c8",
            "tokenizer.json" to "bf1b59b7b11c95f194f51708d918eea378e09d05f84c0e1656dc5180e8117088",
            "tokenizer_config.json" to "2d61ce6c7646881e0e7ef08e3b5dd655a19553ab85c41b1a3c27090a63ff6f49",
            "onnx/model_quantized.onnx" to "24a0b98f4dd4cd92842f5a541272f86f760225a64a29928eddef14bdb2edb986",
        ),
    ),
    NerModelPin(
        "distilbert-conll03",
        "onnx-community/distilbert-NER-ONNX",
        "3a19fe9404a4469d91aa3d551558a97f68872f67",
        mapOf(
            "README.md" to "e0833c918d787ef6b82b62a2bb78640bdbc317156ec4bddef0e179791736cae4",
            "config.json" to "f109facddb205dac712adf5877e4315fae62041bc0916fe808d92abdb594d1fe",
            "tokenizer.json" to "cb26b43c98e8266ae3e99c2a583cf8315d73b33a17e6b20b4df7ff1f22392d34",
            "tokenizer_config.json" to "3c02f7e3bd40e2aca0235ca42256609fd0cd7d883226e17ddea667f1b1314665",
            "onnx/model_quantized.onnx" to "9419a876387ff2bbe5f21ab7429c7bef93eac86c50353390d4d8fca6e4a210d8",
        ),
    ),
)
val chosenNerModel = "distilbert-conll03"

// Outside the repo and the build dir, so `clean` and new worktrees reuse the download.
val modelCache = providers.gradleProperty("modelCacheDir")
    .orElse(providers.environmentVariable("INVOICE_INTEL_MODEL_CACHE"))
    .orElse(System.getProperty("user.home") + "/.cache/invoice-intel/models")

fun modelDir(pin: NerModelPin) = modelCache.map { "$it/${pin.repo}/${pin.revision}" }

val nerDownloads = nerModelPins.associate { pin ->
    pin.name to tasks.register<HuggingFaceDownload>("downloadNerModel-${pin.name}") {
        description = "Downloads the ${pin.name} NER model (${pin.repo}@${pin.revision})."
        repo = pin.repo
        revision = pin.revision
        files = pin.files
        targetDir = layout.dir(modelDir(pin).map { file(it) })
    }
}

tasks.register("downloadNerModel") {
    description = "Downloads the NER model the detector uses (ADR-15)."
    dependsOn(nerDownloads.getValue(chosenNerModel))
}

// The chosen model at a fixed path, for the Docker image and the app's tests and bootRun
// (architecture.md §10, M2.10a), so they don't need to know the cache layout or the pin.
tasks.register<Sync>("stageNerModel") {
    description = "Copies the chosen NER model to build/ner-model (ADR-15)."
    from(nerDownloads.getValue(chosenNerModel))
    into(layout.buildDirectory.dir("ner-model"))
}

// DJL must never download anything at run time (natives come from the jars, ADR-15).
fun Test.useNerModels(names: List<String>) {
    names.forEach { dependsOn(nerDownloads.getValue(it)) }
    systemProperty("ai.djl.offline", "true")
    systemProperty(
        "invoiceintel.ner.models",
        names.joinToString(",") { name -> "$name=" + modelDir(nerModelPins.first { it.name == name }).get() },
    )
}

tasks.test {
    useNerModels(listOf(chosenNerModel))
}

// The redaction-proof test OCRs the redacted PDF (PII-4). JNA doesn't search Homebrew's library
// folder; CI (Linux) finds libtesseract on the default path.
val homebrewLib = file("/opt/homebrew/lib")
if (homebrewLib.isDirectory) {
    tasks.withType<Test>().configureEach {
        systemProperty("jna.library.path", homebrewLib.absolutePath)
    }
}

tasks.register<Test>("nerBenchmark") {
    description = "Compares the NER candidates on synthetic Indian names (ADR-15, A2)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("dev.invoiceintel.pii.NerBenchmarkTest") }
    useNerModels(listOf(chosenNerModel) + nerModelPins.map { it.name }.filter { it != chosenNerModel })
    outputs.upToDateWhen { false }
}

tasks.named("pitest") {
    dependsOn(nerDownloads.getValue(chosenNerModel))
}

pitest {
    jvmArgs.addAll(
        "-Dai.djl.offline=true",
        "-Dinvoiceintel.ner.models=$chosenNerModel=" + modelDir(nerModelPins.first { it.name == chosenNerModel }).get(),
    )
}
