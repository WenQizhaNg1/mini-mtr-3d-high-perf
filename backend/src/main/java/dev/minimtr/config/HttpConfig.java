package dev.minimtr.config;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpConfig {
    @Bean(destroyMethod = "shutdownNow")
    public HttpClient outboundHttpClient(@Value("${outbound.proxy-url:}") String proxyUrl) {
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .proxy(HttpClient.Builder.NO_PROXY);
        if (!proxyUrl.isBlank()) {
            URI proxy;
            try {
                proxy = URI.create(proxyUrl.trim());
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("OUTBOUND_PROXY_URL must be http://host:port");
            }
            if (!"http".equalsIgnoreCase(proxy.getScheme()) || proxy.getHost() == null
                    || proxy.getPort() < 1 || proxy.getPort() > 65535
                    || proxy.getUserInfo() != null || proxy.getQuery() != null || proxy.getFragment() != null
                    || !(proxy.getPath().isEmpty() || proxy.getPath().equals("/"))) {
                throw new IllegalArgumentException("OUTBOUND_PROXY_URL must be http://host:port without credentials or a path");
            }
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        }
        return builder.build();
    }
}
