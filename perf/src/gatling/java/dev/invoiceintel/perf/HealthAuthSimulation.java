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
 * Health and authentication under load (plan M0.15, NFR-3): health is up, anonymous API calls get
 * 401, admin Basic credentials pass security. Pipeline scenarios are added in M4.10.
 */
public final class HealthAuthSimulation extends Simulation {

  private static final PerfConfig CONFIG = PerfConfig.load();

  private final HttpProtocolBuilder protocol =
      http.baseUrl(CONFIG.baseUrl()).acceptHeader("application/json");

  // The document list exists since M1.3; 404 would mean the route went missing.
  private final ScenarioBuilder healthAndAuth =
      scenario("Health and authentication")
          .forever()
          .on(
              exec(http("health").get("/actuator/health").check(status().is(200)))
                  .exec(http("api anonymous").get("/api/documents").check(status().is(401)))
                  .exec(
                      http("api authenticated")
                          .get("/api/documents")
                          .basicAuth(CONFIG.adminUser(), CONFIG.adminPassword())
                          .check(status().is(200)))
                  .pause(Duration.ofSeconds(1)));

  {
    setUp(
            healthAndAuth.injectClosed(
                constantConcurrentUsers(CONFIG.users())
                    .during(Duration.ofSeconds(CONFIG.durationSeconds()))))
        .maxDuration(Duration.ofSeconds(CONFIG.durationSeconds()))
        .protocols(protocol)
        .assertions(
            forAll().responseTime().percentile(95.0).lt(CONFIG.intValue("health-auth.p95-ms")),
            forAll()
                .failedRequests()
                .percent()
                .lte((double) CONFIG.intValue("health-auth.max-error-percent")));
  }
}
