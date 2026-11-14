package backstop;

import org.springframework.context.annotation.*;
import java.time.Instant;
import java.util.Map;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class Security {
    @Bean
    InMemoryUserDetailsManager accounts(Application.Settings settings) {
        if (settings.demoPassword() == null || settings.demoPassword().isBlank()) {
            throw new IllegalArgumentException("Set DEMO_PASSWORD");
        }
        var password = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(settings.demoPassword());
        return new InMemoryUserDetailsManager(
            User.withUsername("ingest").password(password).roles("INGEST").build(),
            User.withUsername("analyst1").password(password).roles("ANALYST").build(),
            User.withUsername("analyst2").password(password).roles("ANALYST").build(),
            User.withUsername("supervisor1").password(password).roles("SUPERVISOR").build());
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}))
            .exceptionHandling(errors -> errors.authenticationEntryPoint((req,res,error) -> {
                res.setStatus(401);
                res.setContentType("application/json");
                res.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"Sign in first\"}");
            }).accessDeniedHandler((req,res,error) -> {
                res.setStatus(403);
                res.setContentType("application/json");
                res.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"Role cannot perform this action\"}");
            }))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST,"/api/login").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/merchant-events").hasAuthority("SCOPE_INGEST")
                .requestMatchers(HttpMethod.POST,"/api/merchants/*/hold").hasAnyAuthority("SCOPE_ANALYST","SCOPE_SUPERVISOR")
                .requestMatchers(HttpMethod.POST,"/api/merchants/*/release").hasAuthority("SCOPE_SUPERVISOR")
                .requestMatchers(HttpMethod.GET,"/api/merchants","/api/merchants/*").hasAnyAuthority("SCOPE_ANALYST","SCOPE_SUPERVISOR")
                .anyRequest().denyAll())
            .build();
    }
    @Bean AuthenticationManager authentication(InMemoryUserDetailsManager accounts) {
        var provider = new DaoAuthenticationProvider(accounts);
        provider.setPasswordEncoder(PasswordEncoderFactories.createDelegatingPasswordEncoder());
        return new ProviderManager(provider);
    }

    @Bean SecretKey jwtKey() throws Exception {
        return KeyGenerator.getInstance("HmacSHA256").generateKey();
    }
    @Bean JwtEncoder jwtEncoder(SecretKey key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }
    @Bean JwtDecoder jwtDecoder(SecretKey key) {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }
}

@RestController
class Login {
    private final AuthenticationManager authentication;
    private final JwtEncoder encoder;
    Login(AuthenticationManager authentication, JwtEncoder encoder) {
        this.authentication = authentication;
        this.encoder = encoder;
    }

    record Credentials(@NotBlank String username, @NotBlank String password) {}

    @PostMapping("/api/login")
    Map<String,String> login(@Valid @RequestBody Credentials credentials) {
        try {
            var account = authentication.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(credentials.username(),credentials.password()));
            var role = account.getAuthorities().iterator().next().getAuthority().substring("ROLE_".length());
            var now = Instant.now();
            var claims = JwtClaimsSet.builder().subject(account.getName()).claim("scope",role)
                .issuedAt(now).expiresAt(now.plusSeconds(3600)).build();
            var jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims));
            return Map.of("token",jwt.getTokenValue(),"role",role);
        } catch (org.springframework.security.core.AuthenticationException invalid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials");
        }
    }
}
