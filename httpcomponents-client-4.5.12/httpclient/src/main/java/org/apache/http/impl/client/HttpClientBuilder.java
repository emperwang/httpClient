/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.impl.client;

import java.io.Closeable;
import java.io.IOException;
import java.net.ProxySelector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.auth.AuthSchemeProvider;
import org.apache.http.client.AuthenticationStrategy;
import org.apache.http.client.BackoffManager;
import org.apache.http.client.ConnectionBackoffStrategy;
import org.apache.http.client.CookieStore;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.ServiceUnavailableRetryStrategy;
import org.apache.http.client.UserTokenHandler;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.InputStreamFactory;
import org.apache.http.client.protocol.RequestAcceptEncoding;
import org.apache.http.client.protocol.RequestAddCookies;
import org.apache.http.client.protocol.RequestAuthCache;
import org.apache.http.client.protocol.RequestClientConnControl;
import org.apache.http.client.protocol.RequestDefaultHeaders;
import org.apache.http.client.protocol.RequestExpectContinue;
import org.apache.http.client.protocol.ResponseContentEncoding;
import org.apache.http.client.protocol.ResponseProcessCookies;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.config.Lookup;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.SchemePortResolver;
import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.LayeredConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.conn.util.PublicSuffixMatcher;
import org.apache.http.conn.util.PublicSuffixMatcherLoader;
import org.apache.http.cookie.CookieSpecProvider;
import org.apache.http.impl.NoConnectionReuseStrategy;
import org.apache.http.impl.auth.BasicSchemeFactory;
import org.apache.http.impl.auth.DigestSchemeFactory;
import org.apache.http.impl.auth.KerberosSchemeFactory;
import org.apache.http.impl.auth.NTLMSchemeFactory;
import org.apache.http.impl.auth.SPNegoSchemeFactory;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.impl.conn.DefaultRoutePlanner;
import org.apache.http.impl.conn.DefaultSchemePortResolver;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.conn.SystemDefaultRoutePlanner;
import org.apache.http.impl.execchain.BackoffStrategyExec;
import org.apache.http.impl.execchain.ClientExecChain;
import org.apache.http.impl.execchain.MainClientExec;
import org.apache.http.impl.execchain.ProtocolExec;
import org.apache.http.impl.execchain.RedirectExec;
import org.apache.http.impl.execchain.RetryExec;
import org.apache.http.impl.execchain.ServiceUnavailableRetryExec;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpProcessorBuilder;
import org.apache.http.protocol.HttpRequestExecutor;
import org.apache.http.protocol.ImmutableHttpProcessor;
import org.apache.http.protocol.RequestContent;
import org.apache.http.protocol.RequestTargetHost;
import org.apache.http.protocol.RequestUserAgent;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.TextUtils;
import org.apache.http.util.VersionInfo;

/**
 * Builder for {@link CloseableHttpClient} instances.
 * <p>
 * When a particular component is not explicitly set this class will
 * use its default implementation. System properties will be taken
 * into account when configuring the default implementations when
 * {@link #useSystemProperties()} method is called prior to calling
 * {@link #build()}.
 * </p>
 * <ul>
 *  <li>ssl.TrustManagerFactory.algorithm</li>
 *  <li>javax.net.ssl.trustStoreType</li>
 *  <li>javax.net.ssl.trustStore</li>
 *  <li>javax.net.ssl.trustStoreProvider</li>
 *  <li>javax.net.ssl.trustStorePassword</li>
 *  <li>ssl.KeyManagerFactory.algorithm</li>
 *  <li>javax.net.ssl.keyStoreType</li>
 *  <li>javax.net.ssl.keyStore</li>
 *  <li>javax.net.ssl.keyStoreProvider</li>
 *  <li>javax.net.ssl.keyStorePassword</li>
 *  <li>https.protocols</li>
 *  <li>https.cipherSuites</li>
 *  <li>http.proxyHost</li>
 *  <li>http.proxyPort</li>
 *  <li>https.proxyHost</li>
 *  <li>https.proxyPort</li>
 *  <li>http.nonProxyHosts</li>
 *  <li>http.keepAlive</li>
 *  <li>http.maxConnections</li>
 *  <li>http.agent</li>
 * </ul>
 * <p>
 * Please note that some settings used by this class can be mutually
 * exclusive and may not apply when building {@link CloseableHttpClient}
 * instances.
 * </p>
 *
 * @since 4.3
 */
public class HttpClientBuilder {

    private HttpRequestExecutor requestExec;
    private HostnameVerifier hostnameVerifier;
    private LayeredConnectionSocketFactory sslSocketFactory;
    private SSLContext sslContext;
    // 记录连接池
    private HttpClientConnectionManager connManager;
    // connectionmanager 是否可以 share
    private boolean connManagerShared;
    private SchemePortResolver schemePortResolver;
    private ConnectionReuseStrategy reuseStrategy;
    private ConnectionKeepAliveStrategy keepAliveStrategy;
    private AuthenticationStrategy targetAuthStrategy;
    private AuthenticationStrategy proxyAuthStrategy;
    private UserTokenHandler userTokenHandler;
    // http处理器, 针对 cookie 等信息进行解析
    private HttpProcessor httpprocessor;
    private DnsResolver dnsResolver;
    // request的拦截器
    private LinkedList<HttpRequestInterceptor> requestFirst;
    private LinkedList<HttpRequestInterceptor> requestLast;
    private LinkedList<HttpResponseInterceptor> responseFirst;
    private LinkedList<HttpResponseInterceptor> responseLast;

    private HttpRequestRetryHandler retryHandler;
    private HttpRoutePlanner routePlanner;
    private RedirectStrategy redirectStrategy;
    private ConnectionBackoffStrategy connectionBackoffStrategy;
    private BackoffManager backoffManager;
    private ServiceUnavailableRetryStrategy serviceUnavailStrategy;
    private Lookup<AuthSchemeProvider> authSchemeRegistry;
    private Lookup<CookieSpecProvider> cookieSpecRegistry;
    private Map<String, InputStreamFactory> contentDecoderMap;
    private CookieStore cookieStore;
    private CredentialsProvider credentialsProvider;
    private String userAgent;
    private HttpHost proxy;
    private Collection<? extends Header> defaultHeaders;
    private SocketConfig defaultSocketConfig;
    private ConnectionConfig defaultConnectionConfig;
    // 记录 request的默认配置
    private RequestConfig defaultRequestConfig;
    private boolean evictExpiredConnections;
    private boolean evictIdleConnections;
    private long maxIdleTime;
    private TimeUnit maxIdleTimeUnit;

    private boolean systemProperties;
    private boolean redirectHandlingDisabled;
    private boolean automaticRetriesDisabled;
    private boolean contentCompressionDisabled;
    private boolean cookieManagementDisabled;
    private boolean authCachingDisabled;
    private boolean connectionStateDisabled;
    private boolean defaultUserAgentDisabled;

    private int maxConnTotal = 0;
    private int maxConnPerRoute = 0;

    private long connTimeToLive = -1;
    private TimeUnit connTimeToLiveTimeUnit = TimeUnit.MILLISECONDS;

    private List<Closeable> closeables;

    private PublicSuffixMatcher publicSuffixMatcher;
    // builder 类
    public static HttpClientBuilder create() {
        return new HttpClientBuilder();
    }

    protected HttpClientBuilder() {
        super();
    }

    /**
     * Assigns {@link HttpRequestExecutor} instance.
     */
    public final HttpClientBuilder setRequestExecutor(final HttpRequestExecutor requestExec) {
        this.requestExec = requestExec;
        return this;
    }

    /**
     * Assigns {@link X509HostnameVerifier} instance.
     * <p>
     * Please note this value can be overridden by the {@link #setConnectionManager(
     *   org.apache.http.conn.HttpClientConnectionManager)} and the {@link #setSSLSocketFactory(
     *   org.apache.http.conn.socket.LayeredConnectionSocketFactory)} methods.
     * </p>
     *
     *   @deprecated (4.4)
     */
    @Deprecated
    public final HttpClientBuilder setHostnameVerifier(final X509HostnameVerifier hostnameVerifier) {
        this.hostnameVerifier = hostnameVerifier;
        return this;
    }

    /**
     * Assigns {@link javax.net.ssl.HostnameVerifier} instance.
     * <p>
     * Please note this value can be overridden by the {@link #setConnectionManager(
     *   org.apache.http.conn.HttpClientConnectionManager)} and the {@link #setSSLSocketFactory(
     *   org.apache.http.conn.socket.LayeredConnectionSocketFactory)} methods.
     * </p>
     *
     *   @since 4.4
     */
    public final HttpClientBuilder setSSLHostnameVerifier(final HostnameVerifier hostnameVerifier) {
        this.hostnameVerifier = hostnameVerifier;
        return this;
    }

    /**
     * Assigns file containing public suffix matcher. Instances of this class can be created
     * with {@link org.apache.http.conn.util.PublicSuffixMatcherLoader}.
     *
     * @see org.apache.http.conn.util.PublicSuffixMatcher
     * @see org.apache.http.conn.util.PublicSuffixMatcherLoader
     *
     *   @since 4.4
     */
    public final HttpClientBuilder setPublicSuffixMatcher(final PublicSuffixMatcher publicSuffixMatcher) {
        this.publicSuffixMatcher = publicSuffixMatcher;
        return this;
    }

    /**
     * Assigns {@link SSLContext} instance.
     * <p>
     * Please note this value can be overridden by the {@link #setConnectionManager(
     *   org.apache.http.conn.HttpClientConnectionManager)} and the {@link #setSSLSocketFactory(
     *   org.apache.http.conn.socket.LayeredConnectionSocketFactory)} methods.
     * </p>
     *
     * @deprecated (4.5) use {@link #setSSLContext(SSLContext)}
     */
    @Deprecated
    public final HttpClientBuilder setSslcontext(final SSLContext sslcontext) {
        return setSSLContext(sslcontext);
    }

    /**
     * Assigns {@link SSLContext} instance.
     * <p>
     * Please note this value can be overridden by the {@link #setConnectionManager(
     *   org.apache.http.conn.HttpClientConnectionManager)} and the {@link #setSSLSocketFactory(
     *   org.apache.http.conn.socket.LayeredConnectionSocketFactory)} methods.
     * </p>
     */
    public final HttpClientBuilder setSSLContext(final SSLContext sslContext) {
        this.sslContext = sslContext;
        return this;
    }

    /**
     * Assigns {@link LayeredConnectionSocketFactory} instance.
     * <p>
     * Please note this value can be overridden by the {@link #setConnectionManager(
     *   org.apache.http.conn.HttpClientConnectionManager)} method.
     * </p>
     */
    public final HttpClientBuilder setSSLSocketFactory(
            final LayeredConnectionSocketFactory sslSocketFactory) {
        this.sslSocketFactory = sslSocketFactory;
        return this;
    }

    /**
     * Assigns maximum total connection value.
     * <p>
     * Please note this value can be overridden by the {@link #setConnectionManager(
     *   org.apache.http.conn.HttpClientConnectionManager)} method.
     * </p>
     */
    public final HttpClientBuilder setMaxConnTotal(final int maxConnTotal) {
        this.maxConnTotal = maxConnTotal;
        return this;
    }

    /**
     * Assigns maximum connection per route value.
     * <p>
     * Please note this value can be overridden by the {@link #setConnectionManager(
     *   org.apache.http.conn.HttpClientConnectionManager)} method.
     * </p>
     */
    public final HttpClientBuilder setMaxConnPerRoute(final int maxConnPerRoute) {
        this.maxConnPerRoute = maxConnPerRoute;
        return this;
    }

    /**
     * Assigns default {@link SocketConfig}.
     * <p>
     * Please note this value can be overridden by the {@link #setConnectionManager(
     *   org.apache.http.conn.HttpClientConnectionManager)} method.
     * </p>
     */
    public final HttpClientBuilder setDefaultSocketConfig(final SocketConfig config) {
        this.defaultSocketConfig = config;
        return this;
    }

    /**
     * Assigns default {@link ConnectionConfig}.
     * <p>
     * Please note this value can be overridden by the {@link #setConnectionManager(
     *   org.apache.http.conn.HttpClientConnectionManager)} method.
     * </p>
     */
    public final HttpClientBuilder setDefaultConnectionConfig(final ConnectionConfig config) {
        this.defaultConnectionConfig = config;
        return this;
    }

    /**
     * Sets maximum time to live for persistent connections
     * <p>
     * Please note this value can be overridden by the {@link #setConnectionManager(
     *   org.apache.http.conn.HttpClientConnectionManager)} method.
     * </p>
     *
     * @since 4.4
     */
    public final HttpClientBuilder setConnectionTimeToLive(final long connTimeToLive, final TimeUnit connTimeToLiveTimeUnit) {
        this.connTimeToLive = connTimeToLive;
        this.connTimeToLiveTimeUnit = connTimeToLiveTimeUnit;
        return this;
    }

    /**
     * Assigns {@link HttpClientConnectionManager} instance.
     */
    // 设置连接池
    public final HttpClientBuilder setConnectionManager(
            final HttpClientConnectionManager connManager) {
        this.connManager = connManager;
        return this;
    }

    /**
     * Defines the connection manager is to be shared by multiple
     * client instances.
     * <p>
     * If the connection manager is shared its life-cycle is expected
     * to be managed by the caller and it will not be shut down
     * if the client is closed.
     * </p>
     *
     * @param shared defines whether or not the connection manager can be shared
     *  by multiple clients.
     *
     * @since 4.4
     */
    public final HttpClientBuilder setConnectionManagerShared(
            final boolean shared) {
        this.connManagerShared = shared;
        return this;
    }

    /**
     * Assigns {@link ConnectionReuseStrategy} instance.
     */
    public final HttpClientBuilder setConnectionReuseStrategy(
            final ConnectionReuseStrategy reuseStrategy) {
        this.reuseStrategy = reuseStrategy;
        return this;
    }

    /**
     * Assigns {@link ConnectionKeepAliveStrategy} instance.
     */
    public final HttpClientBuilder setKeepAliveStrategy(
            final ConnectionKeepAliveStrategy keepAliveStrategy) {
        this.keepAliveStrategy = keepAliveStrategy;
        return this;
    }

    /**
     * Assigns {@link AuthenticationStrategy} instance for target
     * host authentication.
     */
    public final HttpClientBuilder setTargetAuthenticationStrategy(
            final AuthenticationStrategy targetAuthStrategy) {
        this.targetAuthStrategy = targetAuthStrategy;
        return this;
    }

    /**
     * Assigns {@link AuthenticationStrategy} instance for proxy
     * authentication.
     */
    public final HttpClientBuilder setProxyAuthenticationStrategy(
            final AuthenticationStrategy proxyAuthStrategy) {
        this.proxyAuthStrategy = proxyAuthStrategy;
        return this;
    }

    /**
     * Assigns {@link UserTokenHandler} instance.
     * <p>
     * Please note this value can be overridden by the {@link #disableConnectionState()}
     * method.
     * </p>
     */
    public final HttpClientBuilder setUserTokenHandler(final UserTokenHandler userTokenHandler) {
        this.userTokenHandler = userTokenHandler;
        return this;
    }

    /**
     * Disables connection state tracking.
     */
    public final HttpClientBuilder disableConnectionState() {
        connectionStateDisabled = true;
        return this;
    }

    /**
     * Assigns {@link SchemePortResolver} instance.
     */
    public final HttpClientBuilder setSchemePortResolver(
            final SchemePortResolver schemePortResolver) {
        this.schemePortResolver = schemePortResolver;
        return this;
    }

    /**
     * Assigns {@code User-Agent} value.
     * <p>
     * Please note this value can be overridden by the {@link #setHttpProcessor(
     * org.apache.http.protocol.HttpProcessor)} method.
     * </p>
     */
    public final HttpClientBuilder setUserAgent(final String userAgent) {
        this.userAgent = userAgent;
        return this;
    }

    /**
     * Assigns default request header values.
     * <p>
     * Please note this value can be overridden by the {@link #setHttpProcessor(
     * org.apache.http.protocol.HttpProcessor)} method.
     * </p>
     */
    public final HttpClientBuilder setDefaultHeaders(final Collection<? extends Header> defaultHeaders) {
        this.defaultHeaders = defaultHeaders;
        return this;
    }

    /**
     * Adds this protocol interceptor to the head of the protocol processing list.
     * <p>
     * Please note this value can be overridden by the {@link #setHttpProcessor(
     * org.apache.http.protocol.HttpProcessor)} method.
     * </p>
     */
    public final HttpClientBuilder addInterceptorFirst(final HttpResponseInterceptor itcp) {
        if (itcp == null) {
            return this;
        }
        if (responseFirst == null) {
            responseFirst = new LinkedList<HttpResponseInterceptor>();
        }
        responseFirst.addFirst(itcp);
        return this;
    }

    /**
     * Adds this protocol interceptor to the tail of the protocol processing list.
     * <p>
     * Please note this value can be overridden by the {@link #setHttpProcessor(
     * org.apache.http.protocol.HttpProcessor)} method.
     * </p>
     */
    public final HttpClientBuilder addInterceptorLast(final HttpResponseInterceptor itcp) {
        if (itcp == null) {
            return this;
        }
        if (responseLast == null) {
            responseLast = new LinkedList<HttpResponseInterceptor>();
        }
        responseLast.addLast(itcp);
        return this;
    }

    /**
     * Adds this protocol interceptor to the head of the protocol processing list.
     * <p>
     * Please note this value can be overridden by the {@link #setHttpProcessor(
     * org.apache.http.protocol.HttpProcessor)} method.
     */
    public final HttpClientBuilder addInterceptorFirst(final HttpRequestInterceptor itcp) {
        if (itcp == null) {
            return this;
        }
        if (requestFirst == null) {
            requestFirst = new LinkedList<HttpRequestInterceptor>();
        }
        requestFirst.addFirst(itcp);
        return this;
    }

    /**
     * Adds this protocol interceptor to the tail of the protocol processing list.
     * <p>
     * Please note this value can be overridden by the {@link #setHttpProcessor(
     * org.apache.http.protocol.HttpProcessor)} method.
     */
    public final HttpClientBuilder addInterceptorLast(final HttpRequestInterceptor itcp) {
        if (itcp == null) {
            return this;
        }
        if (requestLast == null) {
            requestLast = new LinkedList<HttpRequestInterceptor>();
        }
        requestLast.addLast(itcp);
        return this;
    }

    /**
     * Disables state (cookie) management.
     * <p>
     * Please note this value can be overridden by the {@link #setHttpProcessor(
     * org.apache.http.protocol.HttpProcessor)} method.
     */
    public final HttpClientBuilder disableCookieManagement() {
        this.cookieManagementDisabled = true;
        return this;
    }

    /**
     * Disables automatic content decompression.
     * <p>
     * Please note this value can be overridden by the {@link #setHttpProcessor(
     * org.apache.http.protocol.HttpProcessor)} method.
     */
    public final HttpClientBuilder disableContentCompression() {
        contentCompressionDisabled = true;
        return this;
    }

    /**
     * Disables authentication scheme caching.
     * <p>
     * Please note this value can be overridden by the {@link #setHttpProcessor(
     * org.apache.http.protocol.HttpProcessor)} method.
     */
    public final HttpClientBuilder disableAuthCaching() {
        this.authCachingDisabled = true;
        return this;
    }

    /**
     * Assigns {@link HttpProcessor} instance.
     */
    public final HttpClientBuilder setHttpProcessor(final HttpProcessor httpprocessor) {
        this.httpprocessor = httpprocessor;
        return this;
    }

    /**
     * Assigns {@link DnsResolver} instance.
     * <p>
     * Please note this value can be overridden by the {@link #setConnectionManager(HttpClientConnectionManager)} method.
     */
    public final HttpClientBuilder setDnsResolver(final DnsResolver dnsResolver) {
        this.dnsResolver = dnsResolver;
        return this;
    }

    /**
     * Assigns {@link HttpRequestRetryHandler} instance.
     * <p>
     * Please note this value can be overridden by the {@link #disableAutomaticRetries()}
     * method.
     */
    public final HttpClientBuilder setRetryHandler(final HttpRequestRetryHandler retryHandler) {
        this.retryHandler = retryHandler;
        return this;
    }

    /**
     * Disables automatic request recovery and re-execution.
     */
    public final HttpClientBuilder disableAutomaticRetries() {
        automaticRetriesDisabled = true;
        return this;
    }

    /**
     * Assigns default proxy value.
     * <p>
     * Please note this value can be overridden by the {@link #setRoutePlanner(
     *   org.apache.http.conn.routing.HttpRoutePlanner)} method.
     */
    public final HttpClientBuilder setProxy(final HttpHost proxy) {
        this.proxy = proxy;
        return this;
    }

    /**
     * Assigns {@link HttpRoutePlanner} instance.
     */
    public final HttpClientBuilder setRoutePlanner(final HttpRoutePlanner routePlanner) {
        this.routePlanner = routePlanner;
        return this;
    }

    /**
     * Assigns {@link RedirectStrategy} instance.
     * <p>
     * Please note this value can be overridden by the {@link #disableRedirectHandling()}
     * method.
     * </p>
`     */
    public final HttpClientBuilder setRedirectStrategy(final RedirectStrategy redirectStrategy) {
        this.redirectStrategy = redirectStrategy;
        return this;
    }

    /**
     * Disables automatic redirect handling.
     */
    public final HttpClientBuilder disableRedirectHandling() {
        redirectHandlingDisabled = true;
        return this;
    }

    /**
     * Assigns {@link ConnectionBackoffStrategy} instance.
     */
    public final HttpClientBuilder setConnectionBackoffStrategy(
            final ConnectionBackoffStrategy connectionBackoffStrategy) {
        this.connectionBackoffStrategy = connectionBackoffStrategy;
        return this;
    }

    /**
     * Assigns {@link BackoffManager} instance.
     */
    public final HttpClientBuilder setBackoffManager(final BackoffManager backoffManager) {
        this.backoffManager = backoffManager;
        return this;
    }

    /**
     * Assigns {@link ServiceUnavailableRetryStrategy} instance.
     */
    public final HttpClientBuilder setServiceUnavailableRetryStrategy(
            final ServiceUnavailableRetryStrategy serviceUnavailStrategy) {
        this.serviceUnavailStrategy = serviceUnavailStrategy;
        return this;
    }

    /**
     * Assigns default {@link CookieStore} instance which will be used for
     * request execution if not explicitly set in the client execution context.
     */
    public final HttpClientBuilder setDefaultCookieStore(final CookieStore cookieStore) {
        this.cookieStore = cookieStore;
        return this;
    }

    /**
     * Assigns default {@link CredentialsProvider} instance which will be used
     * for request execution if not explicitly set in the client execution
     * context.
     */
    public final HttpClientBuilder setDefaultCredentialsProvider(
            final CredentialsProvider credentialsProvider) {
        this.credentialsProvider = credentialsProvider;
        return this;
    }

    /**
     * Assigns default {@link org.apache.http.auth.AuthScheme} registry which will
     * be used for request execution if not explicitly set in the client execution
     * context.
     */
    public final HttpClientBuilder setDefaultAuthSchemeRegistry(
            final Lookup<AuthSchemeProvider> authSchemeRegistry) {
        this.authSchemeRegistry = authSchemeRegistry;
        return this;
    }

    /**
     * Assigns default {@link org.apache.http.cookie.CookieSpec} registry which will
     * be used for request execution if not explicitly set in the client execution
     * context.
     *
     * @see org.apache.http.impl.client.CookieSpecRegistries
     *
     */
    public final HttpClientBuilder setDefaultCookieSpecRegistry(
            final Lookup<CookieSpecProvider> cookieSpecRegistry) {
        this.cookieSpecRegistry = cookieSpecRegistry;
        return this;
    }


    /**
     * Assigns a map of {@link org.apache.http.client.entity.InputStreamFactory}s
     * to be used for automatic content decompression.
     */
    public final HttpClientBuilder setContentDecoderRegistry(
            final Map<String, InputStreamFactory> contentDecoderMap) {
        this.contentDecoderMap = contentDecoderMap;
        return this;
    }

    /**
     * Assigns default {@link RequestConfig} instance which will be used
     * for request execution if not explicitly set in the client execution
     * context.
     */
    // 设置 request的默认配置
    public final HttpClientBuilder setDefaultRequestConfig(final RequestConfig config) {
        this.defaultRequestConfig = config;
        return this;
    }

    /**
     * Use system properties when creating and configuring default
     * implementations.
     */
    public final HttpClientBuilder useSystemProperties() {
        this.systemProperties = true;
        return this;
    }

    /**
     * Makes this instance of HttpClient proactively evict expired connections from the
     * connection pool using a background thread.
     * <p>
     * One MUST explicitly close HttpClient with {@link CloseableHttpClient#close()} in order
     * to stop and release the background thread.
     * <p>
     * Please note this method has no effect if the instance of HttpClient is configuted to
     * use a shared connection manager.
     * <p>
     * Please note this method may not be used when the instance of HttpClient is created
     * inside an EJB container.
     *
     * @see #setConnectionManagerShared(boolean)
     * @see org.apache.http.conn.HttpClientConnectionManager#closeExpiredConnections()
     *
     * @since 4.4
     */
    public final HttpClientBuilder evictExpiredConnections() {
        evictExpiredConnections = true;
        return this;
    }

    /**
     * Makes this instance of HttpClient proactively evict idle connections from the
     * connection pool using a background thread.
     * <p>
     * One MUST explicitly close HttpClient with {@link CloseableHttpClient#close()} in order
     * to stop and release the background thread.
     * <p>
     * Please note this method has no effect if the instance of HttpClient is configuted to
     * use a shared connection manager.
     * <p>
     * Please note this method may not be used when the instance of HttpClient is created
     * inside an EJB container.
     *
     * @see #setConnectionManagerShared(boolean)
     * @see org.apache.http.conn.HttpClientConnectionManager#closeExpiredConnections()
     *
     * @param maxIdleTime maximum time persistent connections can stay idle while kept alive
     * in the connection pool. Connections whose inactivity period exceeds this value will
     * get closed and evicted from the pool.
     * @param maxIdleTimeUnit time unit for the above parameter.
     *
     * @deprecated (4.5) use {@link #evictIdleConnections(long, TimeUnit)}
     *
     * @since 4.4
     */
    @Deprecated
    public final HttpClientBuilder evictIdleConnections(final Long maxIdleTime, final TimeUnit maxIdleTimeUnit) {
        return evictIdleConnections(maxIdleTime.longValue(), maxIdleTimeUnit);
    }

    /**
     * Makes this instance of HttpClient proactively evict idle connections from the
     * connection pool using a background thread.
     * <p>
     * One MUST explicitly close HttpClient with {@link CloseableHttpClient#close()} in order
     * to stop and release the background thread.
     * <p>
     * Please note this method has no effect if the instance of HttpClient is configuted to
     * use a shared connection manager.
     * <p>
     * Please note this method may not be used when the instance of HttpClient is created
     * inside an EJB container.
     *
     * @see #setConnectionManagerShared(boolean)
     * @see org.apache.http.conn.HttpClientConnectionManager#closeExpiredConnections()
     *
     * @param maxIdleTime maximum time persistent connections can stay idle while kept alive
     * in the connection pool. Connections whose inactivity period exceeds this value will
     * get closed and evicted from the pool.
     * @param maxIdleTimeUnit time unit for the above parameter.
     *
     * @since 4.4
     */
    public final HttpClientBuilder evictIdleConnections(final long maxIdleTime, final TimeUnit maxIdleTimeUnit) {
        this.evictIdleConnections = true;
        this.maxIdleTime = maxIdleTime;
        this.maxIdleTimeUnit = maxIdleTimeUnit;
        return this;
    }

    /**
     * Disables the default user agent set by this builder if none has been provided by the user.
     *
     * @since 4.5.7
     */
    public final HttpClientBuilder disableDefaultUserAgent() {
        this.defaultUserAgentDisabled = true;
        return this;
    }

    /**
     * Produces an instance of {@link ClientExecChain} to be used as a main exec.
     * <p>
     * Default implementation produces an instance of {@link MainClientExec}
     * </p>
     * <p>
     * For internal use.
     * </p>
     *
     * @since 4.4
     */
    protected ClientExecChain createMainExec(
            final HttpRequestExecutor requestExec,
            final HttpClientConnectionManager connManager,
            final ConnectionReuseStrategy reuseStrategy,
            final ConnectionKeepAliveStrategy keepAliveStrategy,
            final HttpProcessor proxyHttpProcessor,
            final AuthenticationStrategy targetAuthStrategy,
            final AuthenticationStrategy proxyAuthStrategy,
            final UserTokenHandler userTokenHandler)
    {
        return new MainClientExec(
                requestExec,
                connManager,
                reuseStrategy,
                keepAliveStrategy,
                proxyHttpProcessor,
                targetAuthStrategy,
                proxyAuthStrategy,
                userTokenHandler);
    }

    /**
     * For internal use.
     */
    protected ClientExecChain decorateMainExec(final ClientExecChain mainExec) {
        return mainExec;
    }

    /**
     * For internal use.
     */
    protected ClientExecChain decorateProtocolExec(final ClientExecChain protocolExec) {
        return protocolExec;
    }

    /**
     * For internal use.
     */
    protected void addCloseable(final Closeable closeable) {
        if (closeable == null) {
            return;
        }
        if (closeables == null) {
            closeables = new ArrayList<Closeable>();
        }
        closeables.add(closeable);
    }

    private static String[] split(final String s) {
        if (TextUtils.isBlank(s)) {
            return null;
        }
        return s.split(" *, *");
    }
    // 创建httpClient 实例
    public CloseableHttpClient build() {
        // Create main request executor
        // We copy the instance fields to avoid changing them, and rename to avoid accidental use of the wrong version
        // 尾缀匹配器
        PublicSuffixMatcher publicSuffixMatcherCopy = this.publicSuffixMatcher;
        // 1.如果没有配置呢,就使用一个默认值
        if (publicSuffixMatcherCopy == null) {
            publicSuffixMatcherCopy = PublicSuffixMatcherLoader.getDefault();
        }
        // 执行器
        HttpRequestExecutor requestExecCopy = this.requestExec;
        // 2.如果没有设置执行器,则配置默认的执行器
        if (requestExecCopy == null) {
            requestExecCopy = new HttpRequestExecutor();
        }
        // 连接池
        HttpClientConnectionManager connManagerCopy = this.connManager;
        // 3. 如果没有配置连接池,则也会配置一个
        if (connManagerCopy == null) {
            LayeredConnectionSocketFactory sslSocketFactoryCopy = this.sslSocketFactory;
            // 3.1 这里主要是 创建sslSocketFactory
            if (sslSocketFactoryCopy == null) {
                // 3.2  获取配置的系统属性
                // 支持的https 协议
                final String[] supportedProtocols = systemProperties ? split(
                        System.getProperty("https.protocols")) : null;
                // 支持的 https 算法
                final String[] supportedCipherSuites = systemProperties ? split(
                        System.getProperty("https.cipherSuites")) : null;
                // 3.3 设置主机名校验
                HostnameVerifier hostnameVerifierCopy = this.hostnameVerifier;
                // 没有配置主机名校验,则使用默认的校验规则
                if (hostnameVerifierCopy == null) {
                    hostnameVerifierCopy = new DefaultHostnameVerifier(publicSuffixMatcherCopy);
                }
                // 3.4 创建 SSLConnectionSocketFactory
                // 如果设置了 sslContext,就加载了证书的context
                // 那么就使用此指定的 sslContext来常见 sslConnectionSocketFactory
                if (sslContext != null) {
                    sslSocketFactoryCopy = new SSLConnectionSocketFactory(
                            sslContext, supportedProtocols, supportedCipherSuites, hostnameVerifierCopy);
                } else {
                    // 如果没有配置sslContext,但是设置了系统属性,那么就使用系统属性来 创建 SSLConnectionSocketFactory
                    if (systemProperties) {
                        sslSocketFactoryCopy = new SSLConnectionSocketFactory(
                                (SSLSocketFactory) SSLSocketFactory.getDefault(),
                                supportedProtocols, supportedCipherSuites, hostnameVerifierCopy);
                    } else {
                        // 系统属性 sslContext 都没有设置,则创建一个默认的
                        sslSocketFactoryCopy = new SSLConnectionSocketFactory(
                                SSLContexts.createDefault(),
                                hostnameVerifierCopy);
                    }
                }
            }
            // 3.5 创建连接池
            @SuppressWarnings("resource")
            final PoolingHttpClientConnectionManager poolingmgr = new PoolingHttpClientConnectionManager(
                    RegistryBuilder.<ConnectionSocketFactory>create()
                        .register("http", PlainConnectionSocketFactory.getSocketFactory())
                        .register("https", sslSocketFactoryCopy)
                        .build(),
                    null,
                    null,
                    dnsResolver,
                    connTimeToLive,
                    connTimeToLiveTimeUnit != null ? connTimeToLiveTimeUnit : TimeUnit.MILLISECONDS);
            // 3.6 属性以及设置的配置
            if (defaultSocketConfig != null) {
                poolingmgr.setDefaultSocketConfig(defaultSocketConfig);
            }
            if (defaultConnectionConfig != null) {
                poolingmgr.setDefaultConnectionConfig(defaultConnectionConfig);
            }
            if (systemProperties) {
                // 是否设置了 http.keepAlive
                String s = System.getProperty("http.keepAlive", "true");
                if ("true".equalsIgnoreCase(s)) {
                    // 最大连接数
                    s = System.getProperty("http.maxConnections", "5");
                    final int max = Integer.parseInt(s);
                    // 设置默认的 route数为  http.maxConnections
                    poolingmgr.setDefaultMaxPerRoute(max);
                    // 最大连接数为 两倍的 http.maxConnections
                    poolingmgr.setMaxTotal(2 * max);
                }
            }
            // 设置最大连接数
            if (maxConnTotal > 0) {
                poolingmgr.setMaxTotal(maxConnTotal);
            }
            // maxRoute
            if (maxConnPerRoute > 0) {
                poolingmgr.setDefaultMaxPerRoute(maxConnPerRoute);
            }
            // 记录连接池
            connManagerCopy = poolingmgr;
        }
        // 4. 连接重用的策略
        ConnectionReuseStrategy reuseStrategyCopy = this.reuseStrategy;
        // 4.1 如果没有设置 连接重用的策略
        if (reuseStrategyCopy == null) {
            // 4.2 如果使用系统属性,则判断是否设置http.keepAlive
            if (systemProperties) {
                final String s = System.getProperty("http.keepAlive", "true");
                // 如果配置了http.keepAlive,则创建默认额 连接重用策略
                if ("true".equalsIgnoreCase(s)) {
                    reuseStrategyCopy = DefaultClientConnectionReuseStrategy.INSTANCE;
                } else {
                    // 否则 没有连接重用策略
                    reuseStrategyCopy = NoConnectionReuseStrategy.INSTANCE;
                }
            } else {
                // 4.3 如果不使用系统属性,则创建默认的连接重用策略
                reuseStrategyCopy = DefaultClientConnectionReuseStrategy.INSTANCE;
            }
        }
        // 5. keepAlive的 策略
        ConnectionKeepAliveStrategy keepAliveStrategyCopy = this.keepAliveStrategy;
        // 5.1 如果没有设置,则配置一个默认的  keepAlive的策略
        if (keepAliveStrategyCopy == null) {
            keepAliveStrategyCopy = DefaultConnectionKeepAliveStrategy.INSTANCE;
        }
        // 6. 目标机器的认证的策略
        AuthenticationStrategy targetAuthStrategyCopy = this.targetAuthStrategy;
        if (targetAuthStrategyCopy == null) {
            targetAuthStrategyCopy = TargetAuthenticationStrategy.INSTANCE;
        }
        // 7. 代理机器的 认证策略
        AuthenticationStrategy proxyAuthStrategyCopy = this.proxyAuthStrategy;
        if (proxyAuthStrategyCopy == null) {
            proxyAuthStrategyCopy = ProxyAuthenticationStrategy.INSTANCE;
        }
        // 8. useToken的处理
        UserTokenHandler userTokenHandlerCopy = this.userTokenHandler;
        if (userTokenHandlerCopy == null) {
            if (!connectionStateDisabled) {
                userTokenHandlerCopy = DefaultUserTokenHandler.INSTANCE;
            } else {
                userTokenHandlerCopy = NoopUserTokenHandler.INSTANCE;
            }
        }
        // 9. userAgent的处理
        String userAgentCopy = this.userAgent;
        if (userAgentCopy == null) {
            if (systemProperties) {
                userAgentCopy = System.getProperty("http.agent");
            }
            // agent的设置
            if (userAgentCopy == null && !defaultUserAgentDisabled) {
                userAgentCopy = VersionInfo.getUserAgent("Apache-HttpClient",
                        "org.apache.http.client", getClass());
            }
        }
        // 10. 创建主要的执行器
        ClientExecChain execChain = createMainExec(
                requestExecCopy,
                connManagerCopy,
                reuseStrategyCopy,
                keepAliveStrategyCopy,
                new ImmutableHttpProcessor(new RequestTargetHost(), new RequestUserAgent(userAgentCopy)),
                targetAuthStrategyCopy,
                proxyAuthStrategyCopy,
                userTokenHandlerCopy);
        // 11 装饰执行链
        execChain = decorateMainExec(execChain);
        // 12. 创建httpProcessor
        // 这里的 httpProcessor保存了众多的 拦截器,用于对http协议的各种处理
        HttpProcessor httpprocessorCopy = this.httpprocessor;
        if (httpprocessorCopy == null) {
            // 12.1 先创建一个 builder 来创建 httpProcessor
            final HttpProcessorBuilder b = HttpProcessorBuilder.create();
            // 12.2 如果设置了request的拦截器,则添加到 builder中
            if (requestFirst != null) {
                for (final HttpRequestInterceptor i: requestFirst) {
                    b.addFirst(i);
                }
            }
            // 12.3 response的拦截器
            if (responseFirst != null) {
                for (final HttpResponseInterceptor i: responseFirst) {
                    b.addFirst(i);
                }
            }
            // 12.4 添加各种拦截器
            b.addAll(
                    new RequestDefaultHeaders(defaultHeaders),
                    new RequestContent(),
                    new RequestTargetHost(),
                    new RequestClientConnControl(),
                    new RequestUserAgent(userAgentCopy),
                    new RequestExpectContinue());
            // 12.5 cookie 拦截器
            if (!cookieManagementDisabled) {
                b.add(new RequestAddCookies());
            }
            // 允许的 压缩类型 拦截器
            // 是否允许压缩
            // 12.6 允许的压缩类型 拦截器
            if (!contentCompressionDisabled) {
                if (contentDecoderMap != null) {
                    final List<String> encodings = new ArrayList<String>(contentDecoderMap.keySet());
                    Collections.sort(encodings);
                    b.add(new RequestAcceptEncoding(encodings));
                } else {
                    b.add(new RequestAcceptEncoding());
                }
            }
            // 12.7 认证缓存拦截器
            if (!authCachingDisabled) {
                b.add(new RequestAuthCache());
            }
            // 12.8 cookie 管理
            if (!cookieManagementDisabled) {
                b.add(new ResponseProcessCookies());
            }
            // 响应的content 是否允许压缩
            // 12.9 添加responseContent编码
            if (!contentCompressionDisabled) {
                if (contentDecoderMap != null) {
                    final RegistryBuilder<InputStreamFactory> b2 = RegistryBuilder.create();
                    for (final Map.Entry<String, InputStreamFactory> entry: contentDecoderMap.entrySet()) {
                        b2.register(entry.getKey(), entry.getValue());
                    }
                    b.add(new ResponseContentEncoding(b2.build()));
                } else {
                    b.add(new ResponseContentEncoding());
                }
            }
            // 12.10 request最后的一个 拦截器
            if (requestLast != null) {
                for (final HttpRequestInterceptor i: requestLast) {
                    b.addLast(i);
                }
            }
            // 12.11 response的最后一个拦截器
            if (responseLast != null) {
                for (final HttpResponseInterceptor i: responseLast) {
                    b.addLast(i);
                }
            }
            // 12.12 根据 添加的拦截器 创建个月 processor
            httpprocessorCopy = b.build();
        }
        // 对http 协议的处理
        // 13. 装饰者模式, 根据创建的Httpprocessor 来装饰具体的执行器,即在执行前和执行后,调用各种拦截器
        execChain = new ProtocolExec(execChain, httpprocessorCopy);
        // 装饰
        execChain = decorateProtocolExec(execChain);

        // Add request retry executor, if not disabled
        // 14. 如果允许失败重试,则再次使用装饰者模式,对主要执行器进行包装,即catch异常,并进行重试
        if (!automaticRetriesDisabled) {
            HttpRequestRetryHandler retryHandlerCopy = this.retryHandler;
            if (retryHandlerCopy == null) {
                retryHandlerCopy = DefaultHttpRequestRetryHandler.INSTANCE;
            }
            // 14.1 重试机制
            execChain = new RetryExec(execChain, retryHandlerCopy);
        }
        // 15. router 计划者,用来解析route信息,其中就包含了代理
        HttpRoutePlanner routePlannerCopy = this.routePlanner;
        if (routePlannerCopy == null) {
            // scheme对应的port的解析器
            SchemePortResolver schemePortResolverCopy = this.schemePortResolver;
            if (schemePortResolverCopy == null) {
                schemePortResolverCopy = DefaultSchemePortResolver.INSTANCE;
            }
            if (proxy != null) {
                routePlannerCopy = new DefaultProxyRoutePlanner(proxy, schemePortResolverCopy);
            } else if (systemProperties) {
                routePlannerCopy = new SystemDefaultRoutePlanner(
                        schemePortResolverCopy, ProxySelector.getDefault());
            } else {
                routePlannerCopy = new DefaultRoutePlanner(schemePortResolverCopy);
            }
        }

        // Optionally, add service unavailable retry executor
        // 15. 装饰者, 服务不可用时的重试
        final ServiceUnavailableRetryStrategy serviceUnavailStrategyCopy = this.serviceUnavailStrategy;
        if (serviceUnavailStrategyCopy != null) {
            execChain = new ServiceUnavailableRetryExec(execChain, serviceUnavailStrategyCopy);
        }

        // Add redirect executor, if not disabled
        // 16. 重定向的执行 -- 装饰者
        if (!redirectHandlingDisabled) {
            RedirectStrategy redirectStrategyCopy = this.redirectStrategy;
            // 重定向
            if (redirectStrategyCopy == null) {
                redirectStrategyCopy = DefaultRedirectStrategy.INSTANCE;
            }
            execChain = new RedirectExec(execChain, routePlannerCopy, redirectStrategyCopy);
        }

        // Optionally, add connection back-off executor
        // connection backOff
        // 17. 装饰者 -- 重试的间隔
        if (this.backoffManager != null && this.connectionBackoffStrategy != null) {
            execChain = new BackoffStrategyExec(execChain, this.connectionBackoffStrategy, this.backoffManager);
        }
        // 18. auth时的scheme 提供者
        Lookup<AuthSchemeProvider> authSchemeRegistryCopy = this.authSchemeRegistry;
        if (authSchemeRegistryCopy == null) {
            authSchemeRegistryCopy = RegistryBuilder.<AuthSchemeProvider>create()
                .register(AuthSchemes.BASIC, new BasicSchemeFactory())
                .register(AuthSchemes.DIGEST, new DigestSchemeFactory())
                .register(AuthSchemes.NTLM, new NTLMSchemeFactory())
                .register(AuthSchemes.SPNEGO, new SPNegoSchemeFactory())
                .register(AuthSchemes.KERBEROS, new KerberosSchemeFactory())
                .build();
        }
        // 19. cookie 相关
        Lookup<CookieSpecProvider> cookieSpecRegistryCopy = this.cookieSpecRegistry;
        if (cookieSpecRegistryCopy == null) {
            cookieSpecRegistryCopy = CookieSpecRegistries.createDefault(publicSuffixMatcherCopy);
        }
        // 20. cookie 的存储
        CookieStore defaultCookieStore = this.cookieStore;
        if (defaultCookieStore == null) {
            defaultCookieStore = new BasicCookieStore();
        }
        // 21. credential 提供者
        CredentialsProvider defaultCredentialsProvider = this.credentialsProvider;
        if (defaultCredentialsProvider == null) {
            if (systemProperties) {
                defaultCredentialsProvider = new SystemDefaultCredentialsProvider();
            } else {
                defaultCredentialsProvider = new BasicCredentialsProvider();
            }
        }
        // 22. 记录需要关闭的资源
        // 即记录那些需要关闭的资源,当httpClient关闭时,把记录额这些资源也同样关闭
        List<Closeable> closeablesCopy = closeables != null ? new ArrayList<Closeable>(closeables) : null;
        // 连接池 是否是共用的,如果是那么需要用户来进行管理,如果不是,则httpClient 来管理
        if (!this.connManagerShared) {
            if (closeablesCopy == null) {
                closeablesCopy = new ArrayList<Closeable>(1);
            }
            // 记录连接池
            final HttpClientConnectionManager cm = connManagerCopy;
            // 22.1 如果允许 关闭过期和idle超时的连接,则创建一个线程来关闭
            if (evictExpiredConnections || evictIdleConnections) {
                final IdleConnectionEvictor connectionEvictor = new IdleConnectionEvictor(cm,
                        maxIdleTime > 0 ? maxIdleTime : 10, maxIdleTimeUnit != null ? maxIdleTimeUnit : TimeUnit.SECONDS,
                        maxIdleTime, maxIdleTimeUnit);
                // 22.2 把此线程也记录下来,是需要关闭的资源
                closeablesCopy.add(new Closeable() {

                    @Override
                    public void close() throws IOException {
                        connectionEvictor.shutdown();
                        try {
                            connectionEvictor.awaitTermination(1L, TimeUnit.SECONDS);
                        } catch (final InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }

                });
                // 线程开始
                connectionEvictor.start();
            }
            // 22.3 把连接池记录下来
            closeablesCopy.add(new Closeable() {

                @Override
                public void close() throws IOException {
                    cm.shutdown();
                }

            });
        }
        // 23. 创建了 InternalHttpClient
        return new InternalHttpClient(
                execChain,
                connManagerCopy,
                routePlannerCopy,
                cookieSpecRegistryCopy,
                authSchemeRegistryCopy,
                defaultCookieStore,
                defaultCredentialsProvider,
                defaultRequestConfig != null ? defaultRequestConfig : RequestConfig.DEFAULT,
                closeablesCopy);
    }

}
