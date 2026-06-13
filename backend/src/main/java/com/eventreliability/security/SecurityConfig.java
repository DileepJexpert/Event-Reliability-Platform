package com.eventreliability.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Spring Security configuration (§17).
 *
 * <p>Two chains, selected by profile:
 * <ul>
 *   <li><b>default / local</b> — permissive, so the service runs without an IdP for development and
 *       embedded-Kafka tests.</li>
 *   <li><b>{@code secure}</b> — an OIDC resource server validating bank-SSO JWTs, with role-based
 *       authorization: {@code VIEWER} may read, {@code OPERATOR} may perform mutating actions.</li>
 * </ul>
 * Method-level security is enabled in both so controllers can additionally annotate sensitive
 * operations with {@code @PreAuthorize}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Local/dev chain: authentication disabled so the console and tests work without an IdP. */
    @Bean
    @Profile("!secure")
    public SecurityFilterChain localSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * Dev-only CORS so the Flutter web console (and any local origin) can call the REST API and the
     * SSE feed from a browser. Active only under the non-{@code secure} profile — the production
     * ({@code secure}) chain deliberately omits CORS, so a real deployment must configure its own
     * explicit allowed origins.
     */
    @Bean
    @Profile("!secure")
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(List.of("*"));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    /** Production chain: OIDC resource server + role-based authorization. */
    @Bean
    @Profile("secure")
    public SecurityFilterChain secureSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // Maker-checker approvals: any role may read the queue; approve/reject is APPROVER-only.
                        .requestMatchers(HttpMethod.GET, "/api/approvals/**")
                                .hasAnyRole(Roles.VIEWER, Roles.OPERATOR, Roles.APPROVER)
                        .requestMatchers(HttpMethod.POST, "/api/approvals/**").hasRole(Roles.APPROVER)
                        // Reads: either role.
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole(Roles.VIEWER, Roles.OPERATOR)
                        // Maker mutations (replay / bulk-replay / quarantine requests): operators only.
                        .requestMatchers("/api/**").hasRole(Roles.OPERATOR)
                        .requestMatchers("/actuator/**").hasRole(Roles.OPERATOR)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /**
     * Maps OIDC token claims to Spring authorities: standard OAuth2 {@code scope} authorities plus
     * roles from a {@code roles} claim (commonly issued by bank SSO / Keycloak realm roles). The
     * acting username is taken from {@code preferred_username}, falling back to the token subject.
     */
    @Bean
    @Profile("secure")
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        Converter<Jwt, Collection<GrantedAuthority>> authorities = jwt -> {
            Collection<GrantedAuthority> granted = new ArrayList<>(scopes.convert(jwt));
            Object roles = jwt.getClaims().get("roles");
            if (roles instanceof Collection<?> roleList) {
                for (Object role : roleList) {
                    granted.add(new SimpleGrantedAuthority("ROLE_" + role));
                }
            }
            return granted;
        };
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities::convert);
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }
}
