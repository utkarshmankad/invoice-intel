package dev.invoiceintel.eval.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GitShaTest {

  @Test
  void of_commandPrintingSha_returnsIt() {
    String sha = "0123456789abcdef0123456789abcdef01234567";

    assertThat(GitSha.of(List.of("echo", sha))).isEqualTo(sha);
  }

  @Test
  void of_otherOutputFailureOrMissingCommand_returnsUnknown() {
    assertThat(GitSha.of(List.of("echo", "not a sha"))).isEqualTo(GitSha.UNKNOWN);
    assertThat(GitSha.of(List.of("false"))).isEqualTo(GitSha.UNKNOWN);
    assertThat(GitSha.of(List.of("no-such-command-4711"))).isEqualTo(GitSha.UNKNOWN);
  }

  @Test
  void current_returnsShaOrUnknown() {
    assertThat(GitSha.current()).matches("[0-9a-f]{40,64}|" + GitSha.UNKNOWN);
  }
}
