package io.groundedrag.config;

import java.nio.charset.StandardCharsets;

import javax.crypto.spec.SecretKeySpec;

import io.groundedrag.security.ApiKeyAuthenticationFilter;
import io.groundedrag.security.JwtCallerConverter;
import io.groundedrag.security.RateLimitFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Two ways in, one identity out: API keys ({@code X-API-Key}) and, when configured, OIDC/JWT
 * bearer tokens. Both become a {@code Caller}; authorisation and rate limiting see only that.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, RagProperties props) throws Exception {
        RagProperties.Jwt jwt = props.security().jwt();
        http
                // Stateless API authenticated by header: no cookies, so CSRF does not apply.
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyAuthenticationFilter(props.security().apiKeys().stream()
                                .map(k -> new ApiKeyAuthenticationFilter.Credential(k.name(), k.key(), k.spaces()))
                                .toList()),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        // The request was authorised on its original dispatch; when an SSE stream
                        // completes, Spring re-dispatches it as ASYNC (and ERROR on failures). Without
                        // this the API-key filter (skipped on async dispatch) leaves it anonymous and
                        // the stream is cut off mid-response.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/", "/index.html", "/app.js", "/icons.js", "/app.css", "/favicon.svg").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/error").permitAll()
                        .requestMatchers("/actuator/**").hasRole("API")
                        .anyRequest().hasRole("API"))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'")));

        if (jwt.enabled()) {
            http.oauth2ResourceServer(o -> o
                    .jwt(j -> j.decoder(jwtDecoder(jwt)).jwtAuthenticationConverter(new JwtCallerConverter(jwt.spacesClaim())))
                    .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        }
        if (props.rateLimit().enabled()) {
            RateLimitFilter limiter = new RateLimitFilter(props.rateLimit().requestsPerMinute(), props.rateLimit().burst());
            // After both authentication mechanisms, so the bucket is keyed by the resolved caller.
            if (jwt.enabled()) {
                http.addFilterAfter(limiter, BearerTokenAuthenticationFilter.class);
            } else {
                http.addFilterAfter(limiter, ApiKeyAuthenticationFilter.class);
            }
        }
        return http.build();
    }

    static JwtDecoder jwtDecoder(RagProperties.Jwt cfg) {
        NimbusJwtDecoder decoder;
        OAuth2TokenValidator<Jwt> base;
        if (!cfg.issuerUri().isBlank()) {
            // OIDC discovery: keys are fetched from the issuer's JWKS and rotated automatically.
            decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(cfg.issuerUri());
            base = JwtValidators.createDefaultWithIssuer(cfg.issuerUri());
        } else {
            byte[] secret = cfg.hmacSecret().getBytes(StandardCharsets.UTF_8);
            if (secret.length < 32) {
                throw new IllegalStateException("rag.security.jwt.hmac-secret must be at least 32 bytes");
            }
            decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
                    .macAlgorithm(MacAlgorithm.HS256).build();
            base = JwtValidators.createDefault();
        }
        if (!cfg.audience().isBlank()) {
            OAuth2TokenValidator<Jwt> audience = token -> token.getAudience() != null
                    && token.getAudience().contains(cfg.audience())
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong audience", null));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(base, audience));
        } else {
            decoder.setJwtValidator(base);
        }
        return decoder;
    }
}
