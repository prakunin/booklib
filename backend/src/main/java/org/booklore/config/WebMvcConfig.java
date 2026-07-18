package org.booklore.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.booklore.interceptor.KomgaCleanInterceptor;
import org.booklore.interceptor.KomgaEnabledInterceptor;
import org.booklore.interceptor.OpdsEnabledInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.time.Duration;
import java.util.regex.Pattern;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private static final String IMMUTABLE_STATIC_CACHE = CacheControl.maxAge(Duration.ofDays(365))
            .cachePublic()
            .immutable()
            .getHeaderValue();
    private static final Pattern HASHED_ANGULAR_BUNDLE = Pattern.compile(
            "^/(?:main|polyfills|runtime|scripts|styles|vendor|chunk)-[A-Za-z0-9_-]{8,}\\.(?:js|css)$");

    private final OpdsEnabledInterceptor opdsEnabledInterceptor;
    private final KomgaEnabledInterceptor komgaEnabledInterceptor;
    private final KomgaCleanInterceptor komgaCleanInterceptor;

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(new VirtualThreadTaskExecutor("mvc-async-"));
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource resource = location.createRelative(resourcePath);
                        if (resource.exists() && resource.isReadable()) {
                            return resource;
                        }

                        Resource index = new ClassPathResource("/static/index.html");
                        return index.exists() && index.isReadable() ? index : null;
                    }
                });
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (isHashedAngularBundle(request.getRequestURI())) {
                    response.setHeader(HttpHeaders.CACHE_CONTROL, IMMUTABLE_STATIC_CACHE);
                }
                return true;
            }
        });
        registry.addInterceptor(opdsEnabledInterceptor)
                .addPathPatterns("/api/v1/opds/**", "/api/v2/opds/**");
        registry.addInterceptor(komgaEnabledInterceptor)
                .addPathPatterns("/komga/api/**");
        registry.addInterceptor(komgaCleanInterceptor)
                .addPathPatterns("/komga/api/**");
    }

    static boolean isHashedAngularBundle(String requestUri) {
        return requestUri != null && HASHED_ANGULAR_BUNDLE.matcher(requestUri).matches();
    }
}
