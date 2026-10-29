package dev.invoiceintel.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.pii.PiiKeys;
import dev.invoiceintel.pii.PiiType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The throwaway keysets that {@code scripts/ci/stack-credentials.sh} builds with openssl for the CI
 * stack (docs/ci.md §4) are keysets {@link PiiKeys} accepts, so the smoke app can start.
 */
class StackCredentialsScriptTest {

  private static final Pattern EXPORT = Pattern.compile("export (\\w+)='([^']*)'");

  @Test
  void keysets_fromScript_buildTokenGeneratorAndVault() throws Exception {
    Map<String, String> env = runScript();

    var tokens = PiiKeys.tokenGenerator(env);
    var vault = PiiKeys.vault(env);

    String token = tokens.token(PiiType.PHONE, "+91 98765 43210");
    assertThat(token).isEqualTo(tokens.token(PiiType.PHONE, "+91 98765 43210"));
    assertThat(vault.open(vault.seal(token, PiiType.PHONE, "+91 98765 43210")))
        .isEqualTo("+91 98765 43210");
  }

  @Test
  void keysets_twoRuns_differ() throws Exception {
    assertThat(runScript().get(PiiKeys.TOKEN_KEYSET))
        .isNotEqualTo(runScript().get(PiiKeys.TOKEN_KEYSET));
  }

  private static Map<String, String> runScript() throws IOException, InterruptedException {
    Path script = Path.of("..", "scripts", "ci", "stack-credentials.sh").toAbsolutePath();
    var process = new ProcessBuilder("bash", script.toString());
    // In GitHub Actions the script would append to $GITHUB_ENV instead of printing.
    process.environment().remove("GITHUB_ENV");
    Process p = process.redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(p.waitFor()).isZero();
    var env = new HashMap<String, String>();
    EXPORT.matcher(out).results().forEach(m -> env.put(m.group(1), m.group(2)));
    assertThat(env).containsKeys(PiiKeys.TOKEN_KEYSET, PiiKeys.VAULT_KEYSET);
    return env;
  }
}
