package dev.minimtr.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final WorkbenchAccess access;
    public WebConfig(WorkbenchAccess access) { this.access = access; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String path = request.getRequestURI();
                boolean write = !java.util.Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
                if (write || path.startsWith("/api/admin/")) {
                    access.check(request.getHeader("Authorization"));
                    if (write && request.getContentLengthLong() < 0)
                        throw new ResponseStatusException(HttpStatus.LENGTH_REQUIRED, "Content-Length is required");
                    if (write && request.getContentLengthLong() > 6 * 1024 * 1024)
                        throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "JSON body exceeds 6 MiB");
                }
                return true;
            }
        }).addPathPatterns("/api/datasets/**", "/api/styles/**", "/api/admin/**");
    }
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins("*").allowedMethods("GET");
    }
}
