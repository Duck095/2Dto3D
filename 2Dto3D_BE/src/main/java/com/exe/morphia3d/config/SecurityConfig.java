package com.exe.morphia3d.config;

import com.exe.morphia3d.security.JwtAuthFilter;
import com.exe.morphia3d.security.UserDetailsServiceImpl;
import com.exe.morphia3d.feature.FeatureGate;
import com.exe.morphia3d.feature.FeatureGateFilter;
import com.exe.morphia3d.feature.QaMediaUploadFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserDetailsServiceImpl userDetailsService;
    private final JwtAuthFilter jwtAuthFilter;
    private final Environment environment;
    private final FeatureGate featureGate;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Cho phép các port Frontend của bạn (Vite thường dùng 5173, Create React App dùng 3000)
        List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:3000", "http://localhost:5173"));
        if (environment.acceptsProfiles(Profiles.of("dev", "test", "local"))) {
            allowedOrigins.add("http://127.0.0.1:3000");
        }
        if (environment.acceptsProfiles(Profiles.of("qa"))) {
            allowedOrigins.add("http://127.0.0.1:3001");
            allowedOrigins.add("http://127.0.0.1:8088");
        }
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With", "Idempotency-Key"));
        configuration.setAllowCredentials(true); // Cho phép gửi Token/Cookie

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration); // Áp dụng cho mọi endpoint
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        RequestMatcher adminMeshEndpoints = request -> {
            String path = request.getRequestURI();
            String contextPath = request.getContextPath();
            if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
                path = path.substring(contextPath.length());
            }
            if (path == null) return false;
            String[] segments = path.split("/", -1);
            return segments.length == 6 && segments[1].equals("api") && segments[2].equals("admin")
                    && segments[3].equals("products") && !segments[4].isEmpty()
                    && (segments[5].equals("mesh-structure") || segments[5].equals("mesh-configurations"));
        };
        HttpStatusEntryPoint defaultAuthenticationEntryPoint = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
        AccessDeniedHandlerImpl defaultAccessDeniedHandler = new AccessDeniedHandlerImpl();
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            if (adminMeshEndpoints.matches(request)) {
                                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                                response.setContentType("application/json");
                                response.getWriter().write("{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Authentication is required.\"}");
                            } else {
                                defaultAuthenticationEntryPoint.commence(request, response, exception);
                            }
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            if (adminMeshEndpoints.matches(request)) {
                                response.setStatus(HttpStatus.FORBIDDEN.value());
                                response.setContentType("application/json");
                                response.getWriter().write("{\"code\":\"ADMIN_REQUIRED\",\"message\":\"Administrator access is required.\"}");
                            } else {
                                defaultAccessDeniedHandler.handle(request, response, exception);
                            }
                        }))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/materials/**",
                                "/api/colors/**",
                                "/api/products",
                                "/api/products/*",
                                "/api/designer-models",
                                "/api/designer-models/*").permitAll()
                        .requestMatchers("/api/admin/staff/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/tasks/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/products", "/api/admin/products/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/me/tasks/**").hasAuthority("internal.employee.access")
                        .requestMatchers(HttpMethod.GET, "/api/v1/me/staff-profile")
                                .hasAuthority("internal.employee.access")
                        .requestMatchers("/api/accounts/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/admin/designer-models/*/publish").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/colors/**", "/api/materials/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/materials/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/materials/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/designer-models").hasAnyRole("ADMIN", "DESIGNER")
                        .requestMatchers(HttpMethod.POST, "/api/ai-models").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.GET, "/api/ai-models/**").hasAnyRole("ADMIN", "USER")
                        .requestMatchers(HttpMethod.PATCH, "/api/orders/*/status").hasRole("ADMIN")
                        .requestMatchers("/api/orders/**").hasAnyRole("ADMIN", "USER")
                        .requestMatchers("/api/payments/**").hasAnyRole("ADMIN", "USER")
                        .anyRequest().authenticated())
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(new FeatureGateFilter(featureGate), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, BasicAuthenticationFilter.class);

        if (environment.acceptsProfiles(Profiles.of("qa"))) {
            QaMediaActivation activation = QaMediaActivation.resolve(environment);
            http.addFilterBefore(new QaMediaUploadFilter(activation.mode() == QaMediaActivation.Mode.QA_LIVE),
                    SecurityContextHolderFilter.class);
        }

        return http.build();
    }

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtAuthFilter filter) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
