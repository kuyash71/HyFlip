package com.hyflip.server.config;

import java.time.Duration;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Outbound HTTP client shared by all sources. Apache HttpClient decompresses gzip transparently,
 * which cuts Bazaar responses from about 3.4 MB to under 0.5 MB.
 */
@Configuration
public class HttpConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    @Bean(destroyMethod = "close")
    CloseableHttpClient externalHttpClient() {
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.of(CONNECT_TIMEOUT))
                                .setSocketTimeout(Timeout.of(READ_TIMEOUT))
                                .build())
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.of(READ_TIMEOUT))
                        .build())
                .build();
    }

    /**
     * Client for external sources. The User-Agent names the app only, never the user.
     */
    @Bean
    RestClient externalRestClient(RestClient.Builder builder, CloseableHttpClient externalHttpClient,
                                  ObjectProvider<BuildProperties> build) {
        BuildProperties info = build.getIfAvailable();
        String version = info != null ? info.getVersion() : "dev";
        return builder
                .requestFactory(new HttpComponentsClientHttpRequestFactory(externalHttpClient))
                .defaultHeader(HttpHeaders.USER_AGENT, "HyFlip/" + version)
                .build();
    }
}
