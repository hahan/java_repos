/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.http.nio.netty;

import static software.amazon.awssdk.http.HttpMetric.HTTP_CLIENT_NAME;
import static software.amazon.awssdk.http.nio.netty.internal.NettyConfiguration.EVENTLOOP_SHUTDOWN_FUTURE_TIMEOUT_SECONDS;
import static software.amazon.awssdk.http.nio.netty.internal.NettyConfiguration.EVENTLOOP_SHUTDOWN_QUIET_PERIOD_SECONDS;
import static software.amazon.awssdk.http.nio.netty.internal.NettyConfiguration.EVENTLOOP_SHUTDOWN_TIMEOUT_SECONDS;
import static software.amazon.awssdk.utils.FunctionalUtils.invokeSafely;
import static software.amazon.awssdk.utils.FunctionalUtils.runAndLogError;

import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslProvider;
import java.net.SocketOptions;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.annotations.SdkTestInternalApi;
import software.amazon.awssdk.http.Protocol;
import software.amazon.awssdk.http.SdkHttpConfigurationOption;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SystemPropertyTlsKeyManagersProvider;
import software.amazon.awssdk.http.TlsKeyManagersProvider;
import software.amazon.awssdk.http.TlsTrustManagersProvider;
import software.amazon.awssdk.http.async.AsyncExecuteRequest;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.internal.AwaitCloseChannelPoolMap;
import software.amazon.awssdk.http.nio.netty.internal.NettyConfiguration;
import software.amazon.awssdk.http.nio.netty.internal.NettyRequestExecutor;
import software.amazon.awssdk.http.nio.netty.internal.NonManagedEventLoopGroup;
import software.amazon.awssdk.http.nio.netty.internal.RequestContext;
import software.amazon.awssdk.http.nio.netty.internal.SdkChannelOptions;
import software.amazon.awssdk.http.nio.netty.internal.SdkChannelPool;
import software.amazon.awssdk.http.nio.netty.internal.SdkChannelPoolMap;
import software.amazon.awssdk.http.nio.netty.internal.SharedSdkEventLoopGroup;
import software.amazon.awssdk.utils.AttributeMap;
import software.amazon.awssdk.utils.Either;
import software.amazon.awssdk.utils.Validate;

/**
 * An implementation of {@link SdkAsyncHttpClient} that uses a Netty non-blocking HTTP client to communicate with the service.
 *
 * <p>This can be created via {@link #builder()}</p>
 */
@SdkPublicApi
public final class NettyNioAsyncHttpClient implements SdkAsyncHttpClient {

    private static final String CLIENT_NAME = "NettyNio";

    private static final Logger log = LoggerFactory.getLogger(NettyNioAsyncHttpClient.class);
    private static final long MAX_STREAMS_ALLOWED = 4294967295L; // unsigned 32-bit, 2^32 -1
    private static final int DEFAULT_INITIAL_WINDOW_SIZE = 1_048_576; // 1MiB

    // Override connection idle timeout for Netty http client to reduce the frequency of "server failed to complete the
    // response error". see https://github.com/aws/aws-sdk-java-v2/issues/1122
    private static final AttributeMap NETTY_HTTP_DEFAULTS =
        AttributeMap.builder()
                    .put(SdkHttpConfigurationOption.CONNECTION_MAX_IDLE_TIMEOUT, Duration.ofSeconds(5))
                    .build();

    private final SdkEventLoopGroup sdkEventLoopGroup;
    private final SdkChannelPoolMap<URI, ? extends SdkChannelPool> pools;
    private final NettyConfiguration configuration;

    private NettyNioAsyncHttpClient(DefaultBuilder builder, AttributeMap serviceDefaultsMap) {
        this.configuration = new NettyConfiguration(serviceDefaultsMap);
        Protocol protocol = serviceDefaultsMap.get(SdkHttpConfigurationOption.PROTOCOL);
        this.sdkEventLoopGroup = eventLoopGroup(builder);

        Http2Configuration http2Configuration = builder.http2Configuration;

        long maxStreams = resolveMaxHttp2Streams(builder.maxHttp2Streams, http2Configuration);
        int initialWindowSize = resolveInitialWindowSize(http2Configuration);

        this.pools = AwaitCloseChannelPoolMap.builder()
                                             .sdkChannelOptions(builder.sdkChannelOptions)
                                             .configuration(configuration)
                                             .protocol(protocol)
                                             .maxStreams(maxStreams)
                                             .initialWindowSize(initialWindowSize)
                                             .healthCheckPingPeriod(resolveHealthCheckPingPeriod(http2Configuration))
                                             .sdkEventLoopGroup(sdkEventLoopGroup)
                                             .sslProvider(resolveSslProvider(builder))
                                             .proxyConfiguration(builder.proxyConfiguration)
                                             .build();
    }

    @SdkTestInternalApi
    NettyNioAsyncHttpClient(SdkEventLoopGroup sdkEventLoopGroup,
                            SdkChannelPoolMap<URI, ? extends SdkChannelPool> pools,
                            NettyConfiguration configuration) {
        this.sdkEventLoopGroup = sdkEventLoopGroup;
        this.pools = pools;
        this.configuration = configuration;
    }

    @Override
    public CompletableFuture<Void> execute(AsyncExecuteRequest request) {
        RequestContext ctx = createRequestContext(request);
        ctx.metricCollector().reportMetric(HTTP_CLIENT_NAME, clientName()); // TODO: Can't this be done in core?
        return new NettyRequestExecutor(ctx).execute();
    }

    public static Builder builder() {
        return new DefaultBuilder();
    }

    /**
     * Create a {@link NettyNioAsyncHttpClient} with the default properties
     *
     * @return an {@link NettyNioAsyncHttpClient}
     */
    public static SdkAsyncHttpClient create() {
        return new DefaultBuilder().build();
    }

    private RequestContext createRequestContext(AsyncExecuteRequest request) {
        SdkChannelPool pool = pools.get(poolKey(request.request()));
        return new RequestContext(pool, sdkEventLoopGroup.eventLoopGroup(), request, configuration);
    }

    private SdkEventLoopGroup eventLoopGroup(DefaultBuilder builder) {
        Validate.isTrue(builder.eventLoopGroup == null || builder.eventLoopGroupBuilder == null,
                        "The eventLoopGroup and the eventLoopGroupFactory can't both be configured.");
        return Either.fromNullable(builder.eventLoopGroup, builder.eventLoopGroupBuilder)
                     .map(e -> e.map(this::nonManagedEventLoopGroup, SdkEventLoopGroup.Builder::build))
                     .orElseGet(SharedSdkEventLoopGroup::get);
    }

    private static URI poolKey(SdkHttpRequest sdkRequest) {
        return invokeSafely(() -> new URI(sdkRequest.protocol(), null, sdkRequest.host(),
                                          sdkRequest.port(), null, null, null));
    }

    private SslProvider resolveSslProvider(DefaultBuilder builder) {
        if (builder.sslProvider != null) {
            return builder.sslProvider;
        }

        return SslContext.defaultClientProvider();
    }

    private long resolveMaxHttp2Streams(Integer topLevelValue, Http2Configuration http2Configuration) {
        if (topLevelValue != null) {
            return topLevelValue;
        }

        if (http2Configuration == null || http2Configuration.maxStreams() == null) {
            return MAX_STREAMS_ALLOWED;
        }

        return Math.min(http2Configuration.maxStreams(), MAX_STREAMS_ALLOWED);
    }

    private int resolveInitialWindowSize(Http2Configuration http2Configuration) {
        if (http2Configuration == null || http2Configuration.initialWindowSize() == null) {
            return DEFAULT_INITIAL_WINDOW_SIZE;
        }
        return http2Configuration.initialWindowSize();
    }

    private Duration resolveHealthCheckPingPeriod(Http2Configuration http2Configuration) {
        if (http2Configuration != null) {
            return http2Configuration.healthCheckPingPeriod();
        }
        return null;
    }

    private SdkEventLoopGroup nonManagedEventLoopGroup(SdkEventLoopGroup eventLoopGroup) {
        return SdkEventLoopGroup.create(new NonManagedEventLoopGroup(eventLoopGroup.eventLoopGroup()),
                                        eventLoopGroup.channelFactory());
    }

    @Override
    public void close() {
        runAndLogError(log, "Unable to close channel pools", pools::close);
        runAndLogError(log, "Unable to shutdown event loop", () ->
            closeEventLoopUninterruptibly(sdkEventLoopGroup.eventLoopGroup()));
    }

    private void closeEventLoopUninterruptibly(EventLoopGroup eventLoopGroup) throws ExecutionException {
        try {
            eventLoopGroup.shutdownGracefully(EVENTLOOP_SHUTDOWN_QUIET_PERIOD_SECONDS,
                                              EVENTLOOP_SHUTDOWN_TIMEOUT_SECONDS,
                                              TimeUnit.SECONDS)
                          .get(EVENTLOOP_SHUTDOWN_FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            log.error(String.format("Shutting down Netty EventLoopGroup did not complete within %d seconds",
                                    EVENTLOOP_SHUTDOWN_FUTURE_TIMEOUT_SECONDS));
        }
    }

    @Override
    public String clientName() {
        return CLIENT_NAME;
    }

    @SdkTestInternalApi
    NettyConfiguration configuration() {
        return configuration;
    }

    /**
     * Builder that allows configuration of the Netty NIO HTTP implementation. Use {@link #builder()} to configure and construct
     * a Netty HTTP client.
     */
    public interface Builder extends SdkAsyncHttpClient.Builder<NettyNioAsyncHttpClient.Builder> {

        /**
         * Maximum number of allowed concurrent requests. For HTTP/1.1 this is the same as max connections. For HTTP/2
         * the number of connections that will be used depends on the max streams allowed per connection.
         *
         * <p>
         * If the maximum number of concurrent requests is exceeded they may be queued in the HTTP client (see
         * {@link #maxPendingConnectionAcquires(Integer)}</p>) and can cause increased latencies. If the client is overloaded
         * enough such that the pending connection queue fills up, subsequent requests may be rejected or time out
         * (see {@link #connectionAcquisitionTimeout(Duration)}).
         * </p>
         *
         * @param maxConcurrency New value for max concurrency.
         * @return This builder for method chaining.
         */
        Builder maxConcurrency(Integer maxConcurrency);

        /**
         * The maximum number of pending acquires allowed. Once this exceeds, acquire tries will be failed.
         *
         * @param maxPendingAcquires Max number of pending acquires
         * @return This builder for method chaining.
         */
        Builder maxPendingConnectionAcquires(Integer maxPendingAcquires);

        /**
         * The amount of time to wait for a read on a socket before an exception is thrown.
         * Specify {@code Duration.ZERO} to disable.
         *
         * @param readTimeout timeout duration
         * @return this builder for method chaining.
         */
        Builder readTimeout(Duration readTimeout);

        /**
         * The amount of time to wait for a write on a socket before an exception is thrown.
         * Specify {@code Duration.ZERO} to disable.
         *
         * @param writeTimeout timeout duration
         * @return this builder for method chaining.
         */
        Builder writeTimeout(Duration writeTimeout);

        /**
         * The amount of time to wait when initially establishing a connection before giving up and timing out.
         *
         * @param timeout the timeout duration
         * @return this builder for method chaining.
         */
        Builder connectionTimeout(Duration timeout);

        /**
         * The amount of time to wait when acquiring a connection from the pool before giving up and timing out.
         * @param connectionAcquisitionTimeout the timeout duration
         * @return this builder for method chaining.
         */
        Builder connectionAcquisitionTimeout(Duration connectionAcquisitionTimeout);

        /**
         * The maximum amount of time that a connection should be allowed to remain open, regardless of usage frequency.
         *
         * Unlike {@link #readTimeout(Duration)} and {@link #writeTimeout(Duration)}, this will never close a connection that
         * is currently in use, so long-lived connections may remain open longer than this time. In particular, an HTTP/2
         * connection won't be closed as long as there is at least one stream active on the connection.
         */
        Builder connectionTimeToLive(Duration connectionTimeToLive);

        /**
         * Configure the maximum amount of time that a connection should be allowed to remain open while idle. Currently has no
         * effect if {@link #useIdleConnectionReaper(Boolean)} is false.
         *
         * Unlike {@link #readTimeout(Duration)} and {@link #writeTimeout(Duration)}, this will never close a connection that
         * is currently in use, so long-lived connections may remain open longer than this time.
         */
        Builder connectionMaxIdleTime(Duration maxIdleConnectionTimeout);

        /**
         * Configure the maximum amount of time that a TLS handshake is allowed to take from the time the CLIENT HELLO
         * message is sent to the time the client and server have fully negotiated ciphers and exchanged keys.
         * @param tlsNegotiationTimeout the timeout duration
         *
         * <p>
         * By default, it's 10 seconds.
         *
         * @return this builder for method chaining.
         */
        Builder tlsNegotiationTimeout(Duration tlsNegotiationTimeout);

        /**
         * Configure whether the idle connections in the connection pool should be closed.
         * <p>
         * When enabled, connections left idling for longer than {@link #connectionMaxIdleTime(Duration)} will be
         * closed. This will not close connections currently in use. By default, this is enabled.
         */
        Builder useIdleConnectionReaper(Boolean useConnectionReaper);

        /**
         * Sets the {@link SdkEventLoopGroup} to use for the Netty HTTP client. This event loop group may be shared
         * across multiple HTTP clients for better resource and thread utilization. The preferred way to create
         * an {@link EventLoopGroup} is by using the {@link SdkEventLoopGroup#builder()} method which will choose the
         * optimal implementation per the platform.
         *
         * <p>The {@link EventLoopGroup} <b>MUST</b> be closed by the caller when it is ready to
         * be disposed. The SDK will not close the {@link EventLoopGroup} when the HTTP client is closed. See
         * {@link EventLoopGroup#shutdownGracefully()} to properly close the event loop group.</p>
         *
         * <p>This configuration method is only recommended when you wish to share an {@link EventLoopGroup}
         * with multiple clients. If you do not need to share the group it is recommended to use
         * {@link #eventLoopGroupBuilder(SdkEventLoopGroup.Builder)} as the SDK will handle its cleanup when
         * the HTTP client is closed.</p>
         *
         * @param eventLoopGroup Netty {@link SdkEventLoopGroup} to use.
         * @return This builder for method chaining.
         * @see SdkEventLoopGroup
         */
        Builder eventLoopGroup(SdkEventLoopGroup eventLoopGroup);

        /**
         * Sets the {@link SdkEventLoopGroup.Builder} which will be used to create the {@link SdkEventLoopGroup} for the Netty
         * HTTP client. This allows for custom configuration of the Netty {@link EventLoopGroup}.
         *
         * <p>The {@link EventLoopGroup} created by the builder is managed by the SDK and will be shutdown
         * when the HTTP client is closed.</p>
         *
         * <p>This is the preferred configuration method when you just want to customize the {@link EventLoopGroup}
         * but not share it across multiple HTTP clients. If you do wish to share an {@link EventLoopGroup}, see
         * {@link #eventLoopGroup(SdkEventLoopGroup)}</p>
         *
         * @param eventLoopGroupBuilder {@link SdkEventLoopGroup.Builder} to use.
         * @return This builder for method chaining.
         * @see SdkEventLoopGroup.Builder
         */
        Builder eventLoopGroupBuilder(SdkEventLoopGroup.Builder eventLoopGroupBuilder);

        /**
         * Sets the HTTP protocol to use (i.e. HTTP/1.1 or HTTP/2). Not all services support HTTP/2.
         *
         * @param protocol Protocol to use.
         * @return This builder for method chaining.
         */
        Builder protocol(Protocol protocol);

        /**
         * Configure whether to enable or disable TCP KeepAlive.
         * The configuration will be passed to the socket option {@link SocketOptions#SO_KEEPALIVE}.
         * <p>
         * By default, this is disabled.
         * <p>
         * When enabled, the actual KeepAlive mechanism is dependent on the Operating System and therefore additional TCP
         * KeepAlive values (like timeout, number of packets, etc) must be configured via the Operating System (sysctl on
         * Linux/Mac, and Registry values on Windows).
         */
        Builder tcpKeepAlive(Boolean keepConnectionAlive);

        /**
         * Configures additional {@link ChannelOption} which will be used to create Netty Http client. This allows custom
         * configuration for Netty.
         *
         * <p>
         * If a {@link ChannelOption} was previously configured, the old value is replaced.
         *
         * @param channelOption {@link ChannelOption} to set
         * @param value See {@link ChannelOption} to find the type of value for each option
         * @return This builder for method chaining.
         */
        Builder putChannelOption(ChannelOption channelOption, Object value);

        /**
         * Sets the max number of concurrent streams for an HTTP/2 connection. This setting is only respected when the HTTP/2
         * protocol is used.
         *
         * <p>Note that this cannot exceed the value of the MAX_CONCURRENT_STREAMS setting returned by the service. If it
         * does the service setting is used instead.</p>
         *
         * @param maxHttp2Streams Max concurrent HTTP/2 streams per connection.
         * @return This builder for method chaining.
         *
         * @deprecated Use {@link #http2Configuration(Http2Configuration)} along with
         * {@link Http2Configuration.Builder#maxStreams(Long)} instead.
         */
        Builder maxHttp2Streams(Integer maxHttp2Streams);

        /**
         * Sets the {@link SslProvider} to be used in the Netty client.
         *
         * <p>If not configured, {@link SslContext#defaultClientProvider()} will be used to determine the SslProvider.
         *
         * <p>Note that you might need to add other dependencies if not using JDK's default Ssl Provider.
         * See https://netty.io/wiki/requirements-for-4.x.html#transport-security-tls
         *
         * @param sslProvider the SslProvider
         * @return the builder of the method chaining.
         */
        Builder sslProvider(SslProvider sslProvider);

        /**
         * Set the proxy configuration for this client. The configured proxy will be used to proxy any HTTP request
         * destined for any host that does not match any of the hosts in configured non proxy hosts.
         *
         * @param proxyConfiguration The proxy configuration.
         * @return The builder for method chaining.
         * @see ProxyConfiguration#nonProxyHosts()
         */
        Builder proxyConfiguration(ProxyConfiguration proxyConfiguration);

        /**
         * Set the {@link TlsKeyManagersProvider} for this client. The {@code KeyManager}s will be used by the client to
         * authenticate itself with the remote server if necessary when establishing the TLS connection.
         * <p>
         * If no provider is configured, the client will default to {@link SystemPropertyTlsKeyManagersProvider}. To
         * disable any automatic resolution via the system properties, use {@link TlsKeyManagersProvider#noneProvider()}.
         *
         * @param keyManagersProvider The {@code TlsKeyManagersProvider}.
         * @return The builder for method chaining.
         */
        Builder tlsKeyManagersProvider(TlsKeyManagersProvider keyManagersProvider);

        /**
         * Configure the {@link TlsTrustManagersProvider} that will provide the {@link javax.net.ssl.TrustManager}s to use
         * when constructing the SSL context.
         *
         * @param trustManagersProvider The {@code TlsKeyManagersProvider}.
         * @return The builder for method chaining.
         */
        Builder tlsTrustManagersProvider(TlsTrustManagersProvider trustManagersProvider);

        /**
         * Set the HTTP/2 specific configuration for this client.
         * <p>
         * <b>Note:</b>If {@link #maxHttp2Streams(Integer)} and {@link Http2Configuration#maxStreams()} are both set,
         * the value set using {@link #maxHttp2Streams(Integer)} takes precedence.
         *
         * @param http2Configuration The HTTP/2 configuration object.
         * @return the builder for method chaining.
         */
        Builder http2Configuration(Http2Configuration http2Configuration);

        /**
         * Set the HTTP/2 specific configuration for this client.
         * <p>
         * <b>Note:</b>If {@link #maxHttp2Streams(Integer)} and {@link Http2Configuration#maxStreams()} are both set,
         * the value set using {@link #maxHttp2Streams(Integer)} takes precedence.
         *
         * @param http2ConfigurationBuilderConsumer The consumer of the HTTP/2 configuration builder object.
         * @return the builder for method chaining.
         */
        Builder http2Configuration(Consumer<Http2Configuration.Builder> http2ConfigurationBuilderConsumer);
    }

    /**
     * Factory that allows more advanced configuration of the Netty NIO HTTP implementation. Use {@link #builder()} to
     * configure and construct an immutable instance of the factory.
     */
    private static final class DefaultBuilder implements Builder {
        private final AttributeMap.Builder standardOptions = AttributeMap.builder();

        private SdkChannelOptions sdkChannelOptions = new SdkChannelOptions();

        private SdkEventLoopGroup eventLoopGroup;
        private SdkEventLoopGroup.Builder eventLoopGroupBuilder;
        private Integer maxHttp2Streams;
        private Http2Configuration http2Configuration;
        private SslProvider sslProvider;
        private ProxyConfiguration proxyConfiguration;

        private DefaultBuilder() {
        }

        @Override
        public Builder maxConcurrency(Integer maxConcurrency) {
            standardOptions.put(SdkHttpConfigurationOption.MAX_CONNECTIONS, maxConcurrency);
            return this;
        }

        public void setMaxConcurrency(Integer maxConnectionsPerEndpoint) {
            maxConcurrency(maxConnectionsPerEndpoint);
        }

        @Override
        public Builder maxPendingConnectionAcquires(Integer maxPendingAcquires) {
            standardOptions.put(SdkHttpConfigurationOption.MAX_PENDING_CONNECTION_ACQUIRES, maxPendingAcquires);
            return this;
        }

        public void setMaxPendingConnectionAcquires(Integer maxPendingAcquires) {
            maxPendingConnectionAcquires(maxPendingAcquires);
        }

        @Override
        public Builder readTimeout(Duration readTimeout) {
            Validate.isNotNegative(readTimeout, "readTimeout");
            standardOptions.put(SdkHttpConfigurationOption.READ_TIMEOUT, readTimeout);
            return this;
        }

        public void setReadTimeout(Duration readTimeout) {
            readTimeout(readTimeout);
        }

        @Override
        public Builder writeTimeout(Duration writeTimeout) {
            Validate.isNotNegative(writeTimeout, "writeTimeout");
            standardOptions.put(SdkHttpConfigurationOption.WRITE_TIMEOUT, writeTimeout);
            return this;
        }

        public void setWriteTimeout(Duration writeTimeout) {
            writeTimeout(writeTimeout);
        }

        @Override
        public Builder connectionTimeout(Duration timeout) {
            Validate.isPositive(timeout, "connectionTimeout");
            standardOptions.put(SdkHttpConfigurationOption.CONNECTION_TIMEOUT, timeout);
            return this;
        }

        public void setConnectionTimeout(Duration connectionTimeout) {
            connectionTimeout(connectionTimeout);
        }

        @Override
        public Builder connectionAcquisitionTimeout(Duration connectionAcquisitionTimeout) {
            Validate.isPositive(connectionAcquisitionTimeout, "connectionAcquisitionTimeout");
            standardOptions.put(SdkHttpConfigurationOption.CONNECTION_ACQUIRE_TIMEOUT, connectionAcquisitionTimeout);
            return this;
        }

        public void setConnectionAcquisitionTimeout(Duration connectionAcquisitionTimeout) {
            connectionAcquisitionTimeout(connectionAcquisitionTimeout);
        }

        @Override
        public Builder connectionTimeToLive(Duration connectionTimeToLive) {
            Validate.isNotNegative(connectionTimeToLive, "connectionTimeToLive");
            standardOptions.put(SdkHttpConfigurationOption.CONNECTION_TIME_TO_LIVE, connectionTimeToLive);
            return this;
        }

        public void setConnectionTimeToLive(Duration connectionTimeToLive) {
            connectionTimeToLive(connectionTimeToLive);
        }

        @Override
        public Builder connectionMaxIdleTime(Duration connectionMaxIdleTime) {
            Validate.isPositive(connectionMaxIdleTime, "connectionMaxIdleTime");
            standardOptions.put(SdkHttpConfigurationOption.CONNECTION_MAX_IDLE_TIMEOUT, connectionMaxIdleTime);
            return this;
        }

        public void setConnectionMaxIdleTime(Duration connectionMaxIdleTime) {
            connectionMaxIdleTime(connectionMaxIdleTime);
        }

        @Override
        public Builder useIdleConnectionReaper(Boolean useIdleConnectionReaper) {
            standardOptions.put(SdkHttpConfigurationOption.REAP_IDLE_CONNECTIONS, useIdleConnectionReaper);
            return this;
        }

        public void setUseIdleConnectionReaper(Boolean useIdleConnectionReaper) {
            useIdleConnectionReaper(useIdleConnectionReaper);
        }

        @Override
        public Builder tlsNegotiationTimeout(Duration tlsNegotiationTimeout) {
            Validate.isPositive(tlsNegotiationTimeout, "tlsNegotiationTimeout");
            standardOptions.put(SdkHttpConfigurationOption.TLS_NEGOTIATION_TIMEOUT, tlsNegotiationTimeout);
            return this;
        }

        public void setTlsNegotiationTimeout(Duration tlsNegotiationTimeout) {
            tlsNegotiationTimeout(tlsNegotiationTimeout);
        }

        @Override
        public Builder eventLoopGroup(SdkEventLoopGroup eventLoopGroup) {
            this.eventLoopGroup = eventLoopGroup;
            return this;
        }

        public void setEventLoopGroup(SdkEventLoopGroup eventLoopGroup) {
            eventLoopGroup(eventLoopGroup);
        }

        @Override
        public Builder eventLoopGroupBuilder(SdkEventLoopGroup.Builder eventLoopGroupBuilder) {
            this.eventLoopGroupBuilder = eventLoopGroupBuilder;
            return this;
        }

        public void setEventLoopGroupBuilder(SdkEventLoopGroup.Builder eventLoopGroupBuilder) {
            eventLoopGroupBuilder(eventLoopGroupBuilder);
        }

        @Override
        public Builder protocol(Protocol protocol) {
            standardOptions.put(SdkHttpConfigurationOption.PROTOCOL, protocol);
            return this;
        }

        public void setProtocol(Protocol protocol) {
            protocol(protocol);
        }

        @Override
        public Builder tcpKeepAlive(Boolean keepConnectionAlive) {
            standardOptions.put(SdkHttpConfigurationOption.TCP_KEEPALIVE, keepConnectionAlive);
            return this;
        }

        public void setTcpKeepAlive(Boolean keepConnectionAlive) {
            tcpKeepAlive(keepConnectionAlive);
        }

        @Override
        public Builder putChannelOption(ChannelOption channelOption, Object value) {
            this.sdkChannelOptions.putOption(channelOption, value);
            return this;
        }

        @Override
        public Builder maxHttp2Streams(Integer maxHttp2Streams) {
            this.maxHttp2Streams = maxHttp2Streams;
            return this;
        }

        public void setMaxHttp2Streams(Integer maxHttp2Streams) {
            maxHttp2Streams(maxHttp2Streams);
        }

        @Override
        public Builder sslProvider(SslProvider sslProvider) {
            this.sslProvider = sslProvider;
            return this;
        }

        public void setSslProvider(SslProvider sslProvider) {
            sslProvider(sslProvider);
        }

        @Override
        public Builder proxyConfiguration(ProxyConfiguration proxyConfiguration) {
            this.proxyConfiguration = proxyConfiguration;
            return this;
        }

        public void setProxyConfiguration(ProxyConfiguration proxyConfiguration) {
            proxyConfiguration(proxyConfiguration);
        }

        @Override
        public Builder tlsKeyManagersProvider(TlsKeyManagersProvider tlsKeyManagersProvider) {
            this.standardOptions.put(SdkHttpConfigurationOption.TLS_KEY_MANAGERS_PROVIDER, tlsKeyManagersProvider);
            return this;
        }

        public void setTlsKeyManagersProvider(TlsKeyManagersProvider tlsKeyManagersProvider) {
            tlsKeyManagersProvider(tlsKeyManagersProvider);
        }

        @Override
        public Builder tlsTrustManagersProvider(TlsTrustManagersProvider tlsTrustManagersProvider) {
            standardOptions.put(SdkHttpConfigurationOption.TLS_TRUST_MANAGERS_PROVIDER, tlsTrustManagersProvider);
            return this;
        }

        public void setTlsTrustManagersProvider(TlsTrustManagersProvider tlsTrustManagersProvider) {
            tlsTrustManagersProvider(tlsTrustManagersProvider);
        }

        @Override
        public Builder http2Configuration(Http2Configuration http2Configuration) {
            this.http2Configuration = http2Configuration;
            return this;
        }

        @Override
        public Builder http2Configuration(Consumer<Http2Configuration.Builder> http2ConfigurationBuilderConsumer) {
            Http2Configuration.Builder builder = Http2Configuration.builder();
            http2ConfigurationBuilderConsumer.accept(builder);
            return http2Configuration(builder.build());
        }

        public void setHttp2Configuration(Http2Configuration http2Configuration) {
            http2Configuration(http2Configuration);
        }

        @Override
        public SdkAsyncHttpClient buildWithDefaults(AttributeMap serviceDefaults) {
            return new NettyNioAsyncHttpClient(this, standardOptions.build()
                                                                    .merge(serviceDefaults)
                                                                    .merge(NETTY_HTTP_DEFAULTS)
                                                                    .merge(SdkHttpConfigurationOption.GLOBAL_HTTP_DEFAULTS));

        }
    }
}
