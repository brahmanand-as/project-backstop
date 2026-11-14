package backstop;

import org.springframework.context.annotation.*;
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
            .httpBasic(basic -> basic.authenticationEntryPoint((req,res,error) -> {
                res.setHeader("WWW-Authenticate","Basic realm=\"Backstop\"");
                res.setStatus(401);
                res.setContentType("application/json");
                res.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"Sign in first\"}");
            }))
            .exceptionHandling(errors -> errors.accessDeniedHandler((req,res,error) -> {
                res.setStatus(403);
                res.setContentType("application/json");
                res.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"Role cannot perform this action\"}");
            }))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST,"/api/merchant-events").hasRole("INGEST")
                .requestMatchers(HttpMethod.POST,"/api/merchants/*/hold").hasAnyRole("ANALYST","SUPERVISOR")
                .requestMatchers(HttpMethod.POST,"/api/merchants/*/release").hasRole("SUPERVISOR")
                .requestMatchers(HttpMethod.GET,"/api/merchants","/api/merchants/*").hasAnyRole("ANALYST","SUPERVISOR")
                .anyRequest().denyAll())
            .build();
    }
}
