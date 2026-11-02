package dev.invoiceintel.perf;

import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.forAll;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.ByteArrayBodyPart;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;

/**
 * Upload and read API under load (plan M4.10, NFR-3, docs/ci.md §5). Each virtual user uploads one
 * new synthetic invoice, then loops: re-upload it (deduplicated, ING-2), list documents, read its
 * detail. Only the first upload per user starts the pipeline, so pipeline work stays at one
 * document per user and doesn't skew the API latencies measured here.
 */
public final class UploadReadSimulation extends Simulation {

  private static final PerfConfig CONFIG = PerfConfig.load();

  private final HttpProtocolBuilder protocol =
      http.baseUrl(CONFIG.baseUrl())
          .acceptHeader("application/json")
          .basicAuth(CONFIG.adminUser(), CONFIG.adminPassword());

  private static ChainBuilder upload(String name, boolean duplicate) {
    return exec(
        http(name)
            .post("/api/documents")
            .bodyPart(
                ByteArrayBodyPart("files", session -> session.<byte[]>get("pdf"))
                    .fileName(SyntheticInvoice.FILE_NAME)
                    .contentType("application/pdf"))
            .asMultipartForm()
            .check(
                status().is(200),
                jsonPath("$[0].id").saveAs("id"),
                jsonPath("$[0].duplicate").ofBoolean().is(duplicate)));
  }

  private final ScenarioBuilder uploadAndRead =
      scenario("Upload and read")
          .exec(session -> session.set("pdf", SyntheticInvoice.unique()))
          .exec(upload("upload new", false))
          .forever()
          .on(
              exec(upload("upload duplicate", true))
                  .exec(http("list documents").get("/api/documents").check(status().is(200)))
                  .exec(
                      http("document detail")
                          .get("/api/documents/#{id}")
                          .check(status().is(200), jsonPath("$.id").isEL("#{id}")))
                  .pause(Duration.ofSeconds(1)));

  {
    setUp(
            uploadAndRead.injectClosed(
                constantConcurrentUsers(CONFIG.users())
                    .during(Duration.ofSeconds(CONFIG.durationSeconds()))))
        .maxDuration(Duration.ofSeconds(CONFIG.durationSeconds()))
        .protocols(protocol)
        .assertions(
            forAll().responseTime().percentile(95.0).lt(CONFIG.intValue("upload-read.p95-ms")),
            forAll()
                .failedRequests()
                .percent()
                .lte((double) CONFIG.intValue("upload-read.max-error-percent")));
  }
}
