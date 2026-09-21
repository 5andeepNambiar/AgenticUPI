package com.agenticupi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final HmacAuthenticationFilter hmacAuthenticationFilter;

    public SecurityConfig(HmacAuthenticationFilter hmacAuthenticationFilter) {
        this.hmacAuthenticationFilter = hmacAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/v1/agent/execute-payment").permitAll()
                // Let other API endpoints be handled internally or require proper roles eventually
                .anyRequest().permitAll()
            )
            .addFilterBefore(hmacAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
