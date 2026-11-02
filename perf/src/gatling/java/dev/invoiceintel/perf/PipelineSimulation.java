package dev.invoiceintel.perf;

import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.ByteArrayBodyPart;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * End-to-end time to decision (plan M4.10, NFR-3, docs/ci.md §5): each virtual user uploads one new
 * digital-PDF invoice and polls its status until the pipeline decides, against the CI stack's LLM
 * stub (800 ms per call).
 *
 * <p>Gatling asserts on groups by cumulated response time, which leaves out the polling pauses, so
 * the time to decision is checked per document instead: the {@code time to decision} request fails
 * when its document took longer than the limit, and "p95 under the limit" is "at most 5% of those
 * requests failed". Any document that ends FAILED or never decides fails {@code decision}, which
 * must stay at zero failures. The clock starts before the upload, so queue wait counts too
 * (stricter than NFR-3).
 */
public final class PipelineSimulation extends Simulation {

  private static final PerfConfig CONFIG = PerfConfig.load();
  private static final Set<String> DECIDED = Set.of("AUTO_APPROVED", "NEEDS_REVIEW");
  private static final Set<String> FINAL = Set.of("AUTO_APPROVED", "NEEDS_REVIEW", "FAILED");

  private static final int DOCUMENTS = CONFIG.profileInt("pipeline-documents");
  private static final int RAMP_SECONDS = CONFIG.profileInt("pipeline-ramp-seconds");
  private static final long LIMIT_MS = CONFIG.intValue("pipeline.decision-p95-ms");
  private static final int TIMEOUT_SECONDS = CONFIG.intValue("pipeline.timeout-seconds");
  private static final Duration POLL = Duration.ofMillis(500);

  private static final Queue<Long> ELAPSED_MS = new ConcurrentLinkedQueue<>();

  private final HttpProtocolBuilder protocol =
      http.baseUrl(CONFIG.baseUrl())
          .acceptHeader("application/json")
          .basicAuth(CONFIG.adminUser(), CONFIG.adminPassword());

  private final ScenarioBuilder toDecision =
      scenario("Time to decision")
          .exec(
              session ->
                  session
                      .set("pdf", SyntheticInvoice.unique())
                      .set("status", "")
                      .set("start", System.currentTimeMillis()))
          .exec(
              http("upload")
                  .post("/api/documents")
                  .bodyPart(
                      ByteArrayBodyPart("files", session -> session.<byte[]>get("pdf"))
                          .fileName(SyntheticInvoice.FILE_NAME)
                          .contentType("application/pdf"))
                  .asMultipartForm()
                  .check(
                      status().is(200),
                      jsonPath("$[0].id").saveAs("id"),
                      jsonPath("$[0].duplicate").ofBoolean().is(false)))
          .exitHereIfFailed()
          .asLongAsDuring(
              session -> !FINAL.contains(session.getString("status")),
              Duration.ofSeconds(TIMEOUT_SECONDS))
          .on(
              pause(POLL)
                  .exec(
                      http("poll status")
                          .get("/api/documents/#{id}")
                          .check(
                              status().is(200),
                              jsonPath("$.status").saveAs("status"),
                              // Stage and code only (PII-8): names a failure in the console.
                              jsonPath("$.failure.stage").optional().saveAs("failedStage"),
                              jsonPath("$.failure.code").optional().saveAs("failureCode"))))
          .exec(PipelineSimulation::recordElapsed)
          .exec(
              http("decision")
                  .get("/api/documents/#{id}")
                  .check(
                      status().is(200),
                      jsonPath("$.status").validate("decided", PipelineSimulation::decided)))
          .exec(
              http("time to decision")
                  .get("/api/documents/#{id}")
                  .check(
                      status()
                          .validate(
                              "within limit",
                              (code, session) -> withinLimit(session.getLong("elapsedMs")))));

  private static Session recordElapsed(Session session) {
    long elapsed = System.currentTimeMillis() - session.getLong("start");
    ELAPSED_MS.add(elapsed);
    return session.set("elapsedMs", elapsed);
  }

  /** Fails a document that isn't decided; the message leads with the failed stage and code. */
  private static String decided(String status, Session session) {
    if (!DECIDED.contains(status)) {
      throw new IllegalStateException(
          status + " " + session.getString("failedStage") + " " + session.getString("failureCode"));
    }
    return status;
  }

  private static Integer withinLimit(long elapsedMs) {
    if (elapsedMs >= LIMIT_MS) {
      throw new IllegalStateException(elapsedMs + " ms to decision, limit " + LIMIT_MS + " ms");
    }
    return 200;
  }

  {
    setUp(toDecision.injectOpen(rampUsers(DOCUMENTS).during(Duration.ofSeconds(RAMP_SECONDS))))
        .protocols(protocol)
        .assertions(
            details("upload").failedRequests().count().is(0L),
            details("poll status").failedRequests().count().is(0L),
            details("decision").failedRequests().count().is(0L),
            details("decision").allRequests().count().is((long) DOCUMENTS),
            details("time to decision").failedRequests().percent().lte(5.0));
  }

  @Override
  public void after() {
    List<Long> sorted = new ArrayList<>(ELAPSED_MS);
    sorted.sort(null);
    if (!sorted.isEmpty()) {
      long p50 = sorted.get((int) Math.ceil(0.50 * sorted.size()) - 1);
      long p95 = sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1);
      System.out.printf(
          "Time to decision: %d documents, p50 %d ms, p95 %d ms, limit %d ms%n",
          sorted.size(), p50, p95, LIMIT_MS);
    }
  }
}
