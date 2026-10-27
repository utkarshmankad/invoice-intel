package dev.invoiceintel.app.security;

import dev.invoiceintel.app.config.InvoiceIntelProperties;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * One admin user; HTTP Basic for {@code /api/**} so the eval CLI, smoke and performance tests need
 * no browser session, form login with CSRF for everything else. Only health is public (NFR-2,
 * architecture.md §11). Servlet web applications only, so non-web runs (eval CLI, tests) need no
 * {@link HttpSecurity}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
public class SecurityConfig {

  /**
   * CSRF is off because the chain is stateless: no session cookie exists for a forged request to
   * ride on. The remaining CSRF route is a browser that cached Basic credentials from its login
   * prompt, so the 401 carries no {@code WWW-Authenticate} challenge and browsers never show that
   * prompt (ADR-12). Clients send the {@code Authorization} header up front.
   */
  @Bean
  @Order(1)
  @SuppressFBWarnings(
      value = "SPRING_CSRF_PROTECTION_DISABLED",
      justification = "Stateless Basic API without a browser credential prompt (ADR-12)")
  SecurityFilterChain apiSecurity(HttpSecurity http) {
    return http.securityMatcher("/api/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .httpBasic(
            basic ->
                basic.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain uiSecurity(HttpSecurity http) {
    return http.authorizeHttpRequests(
            auth ->
                // `/error` renders the status of a rejected request (401, 403); guarding it
                // would turn every rejection into a login redirect.
                auth.requestMatchers("/actuator/health", "/actuator/health/**", "/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .formLogin(Customizer.withDefaults())
        .logout(Customizer.withDefaults())
        .build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  UserDetailsService adminUser(InvoiceIntelProperties properties, PasswordEncoder encoder) {
    var security = properties.security();
    // Only the hash is kept in memory; the plain password stays in the environment.
    return new InMemoryUserDetailsManager(
        User.withUsername(security.adminUsername())
            .password(encoder.encode(security.adminPassword()))
            .roles("ADMIN")
            .build());
  }
}
