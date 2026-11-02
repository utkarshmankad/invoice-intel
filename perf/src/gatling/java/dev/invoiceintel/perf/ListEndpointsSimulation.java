package dev.invoiceintel.perf;

import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.forAll;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;

/**
 * Review queue and list endpoints under load (plan M4.10, docs/ci.md §5): all documents, the review
 * queue ({@code NEEDS_REVIEW}, REV-1) and auto-approved ones. The review UI page (M7.2) reads the
 * same query, so the API list stands in for it until then.
 */
public final class ListEndpointsSimulation extends Simulation {

  private static final PerfConfig CONFIG = PerfConfig.load();

  private final HttpProtocolBuilder protocol =
      http.baseUrl(CONFIG.baseUrl())
          .acceptHeader("application/json")
          .basicAuth(CONFIG.adminUser(), CONFIG.adminPassword());

  private final ScenarioBuilder lists =
      scenario("List endpoints")
          .forever()
          .on(
              exec(http("list all").get("/api/documents").check(status().is(200)))
                  .exec(
                      http("review queue")
                          .get("/api/documents?status=NEEDS_REVIEW")
                          .check(status().is(200)))
                  .exec(
                      http("list auto-approved")
                          .get("/api/documents?status=AUTO_APPROVED")
                          .check(status().is(200)))
                  .pause(Duration.ofSeconds(1)));

  {
    setUp(
            lists.injectClosed(
                constantConcurrentUsers(CONFIG.users())
                    .during(Duration.ofSeconds(CONFIG.durationSeconds()))))
        .maxDuration(Duration.ofSeconds(CONFIG.durationSeconds()))
        .protocols(protocol)
        .assertions(
            forAll().responseTime().percentile(95.0).lt(CONFIG.intValue("lists.p95-ms")),
            forAll()
                .failedRequests()
                .percent()
                .lte((double) CONFIG.intValue("lists.max-error-percent")));
  }
}
