package com.postalid.verification.config;

import com.postalid.verification.web.RateLimitFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.security.web.session.HttpSessionEventPublisher;

import java.util.List;

/**
 * The two application-facing filter chains.
 *
 * <p>They are separate because they authenticate different kinds of caller and therefore need
 * opposite settings. The API chain is stateless and bearer-token driven, so it has no session for
 * CSRF to defend; the web chain is session-based and browser-driven, so CSRF protection is
 * essential. Collapsing them into one chain would force a compromise on both.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String SCOPE_VERIFY = "SCOPE_postalid.verify";
    private static final String SCOPE_IMAGES = "SCOPE_postalid.images";

    private static final String[] API_DOC_PATHS = {
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"
    };

    /**
     * Machine clients: bearer token in, JSON out, no session, no redirect to a login page.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain apiFilterChain(HttpSecurity http, RateLimitFilter rateLimitFilter) throws Exception {
        http
                .securityMatcher("/api/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/verify").hasAuthority(SCOPE_VERIFY)
                        .requestMatchers("/api/v1/images/**").hasAuthority(SCOPE_IMAGES)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // No session and no cookie, so there is nothing for a cross-site request to ride on.
                .csrf(csrf -> csrf.disable())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class);

        applyCommonHeaders(http);
        return http.build();
    }

    /**
     * Operators using the browser form. Session-based, CSRF-protected, and locked down to
     * authenticated users everywhere except the login page and static assets.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain webFilterChain(HttpSecurity http,
                                              RateLimitFilter rateLimitFilter,
                                              WebUiProperties webUiProperties) throws Exception {
        http
                .authorizeHttpRequests(auth -> {
                    // Container-generated error forwards. Without this an anonymous 404 is
                    // redirected to the login page, which tells a caller that every unknown path
                    // needs a session and turns a missing asset into a confusing bounce.
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();

                    // Branding assets must load on the login page, before a session exists.
                    auth.requestMatchers("/login", "/css/**", "/img/**", "/favicon.ico").permitAll();
                    auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();

                    // The API contract describes an endpoint over personal data. An operator
                    // session is enough to read it; anonymous access is opt-in.
                    if (webUiProperties.isPublicApiDocs()) {
                        auth.requestMatchers(API_DOC_PATHS).permitAll();
                    }

                    auth.anyRequest().authenticated();
                })
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .permitAll())
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?loggedOut")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))
                .sessionManagement(session -> session
                        .sessionFixation(sessionFixation -> sessionFixation.newSession())
                        // Concurrent-session control depends on the container telling Spring
                        // Security when a session ends; see httpSessionEventPublisher below.
                        // Without that the registry never frees a slot and an operator who has
                        // simply closed their browser twice is locked out until expiry.
                        .maximumSessions(2)
                        .maxSessionsPreventsLogin(false))
                .csrf(Customizer.withDefaults())
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class);

        applyCommonHeaders(http);
        return http.build();
    }

    /**
     * Response headers applied to both chains.
     *
     * <p>The content security policy allows nothing off-origin, which is why the UI ships its own
     * stylesheet rather than pulling one from a CDN. Images are restricted to the same origin so
     * a cardholder photograph cannot be pointed at a third-party host.
     */
    private void applyCommonHeaders(HttpSecurity http) throws Exception {
        http.headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(String.join("; ",
                        "default-src 'self'",
                        "script-src 'self'",
                        "style-src 'self'",
                        "img-src 'self' data:",
                        "font-src 'self'",
                        "connect-src 'self'",
                        "form-action 'self'",
                        "frame-ancestors 'none'",
                        "base-uri 'self'",
                        "object-src 'none'")))
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(Customizer.withDefaults())
                .referrerPolicy(referrer -> referrer
                        .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .xssProtection(xss -> xss
                        .headerValue(XXssProtectionHeaderWriter.HeaderValue.DISABLED))
                .httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .maxAgeInSeconds(31536000))
                .permissionsPolicyHeader(permissions -> permissions
                        .policy("camera=(), microphone=(), geolocation=(), payment=()")));
    }

    /**
     * Publishes container session lifecycle events to Spring Security.
     *
     * <p>Required for {@code maximumSessions} to work: without it the session registry never
     * learns that a session was destroyed, so closed browsers keep occupying slots and the limit
     * eventually locks the operator out of their own console.
     */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /**
     * BCrypt at a deliberately raised cost. It hashes both operator passwords and OAuth client
     * secrets, and a client secret is checked on every token request, so the cost is a real
     * throughput consideration rather than a free win — 12 is a reasonable place to sit.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * Operator accounts for the web form.
     *
     * <p>In-memory and driven by configuration, which is honest about what this is: the UI is an
     * operator console over an API that carries the real authorization model. A deployment with
     * more than a handful of operators should replace this bean with a directory-backed one.
     */
    @Bean
    public UserDetailsService userDetailsService(WebUiProperties properties, PasswordEncoder passwordEncoder) {
        List<org.springframework.security.core.userdetails.UserDetails> users = properties.getOperators().entrySet()
                .stream()
                .map(entry -> (org.springframework.security.core.userdetails.UserDetails) User
                        .withUsername(entry.getKey())
                        .password(passwordEncoder.encode(entry.getValue().getPassword()))
                        .roles("OPERATOR")
                        .build())
                .toList();

        if (users.isEmpty()) {
            throw new IllegalStateException(
                    "No web operators configured. Declare at least one under postalid.web.operators.");
        }
        return new InMemoryUserDetailsManager(users);
    }
}
