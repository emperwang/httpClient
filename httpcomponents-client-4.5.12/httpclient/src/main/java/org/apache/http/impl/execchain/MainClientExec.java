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

package org.apache.http.impl.execchain;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpClientConnection;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.annotation.Contract;
import org.apache.http.annotation.ThreadingBehavior;
import org.apache.http.auth.AUTH;
import org.apache.http.auth.AuthProtocolState;
import org.apache.http.auth.AuthState;
import org.apache.http.client.AuthenticationStrategy;
import org.apache.http.client.NonRepeatableRequestException;
import org.apache.http.client.UserTokenHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpExecutionAware;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.ConnectionRequest;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.routing.BasicRouteDirector;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.routing.HttpRouteDirector;
import org.apache.http.conn.routing.RouteTracker;
import org.apache.http.entity.BufferedHttpEntity;
import org.apache.http.impl.auth.HttpAuthenticator;
import org.apache.http.impl.conn.ConnectionShutdownException;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpRequestExecutor;
import org.apache.http.protocol.ImmutableHttpProcessor;
import org.apache.http.protocol.RequestTargetHost;
import org.apache.http.util.Args;
import org.apache.http.util.EntityUtils;

/**
 * The last request executor in the HTTP request execution chain
 * that is responsible for execution of request / response
 * exchanges with the opposite endpoint.
 * This executor will automatically retry the request in case
 * of an authentication challenge by an intermediate proxy or
 * by the target server.
 *
 * @since 4.3
 */
@SuppressWarnings("deprecation")
@Contract(threading = ThreadingBehavior.IMMUTABLE_CONDITIONAL)
public class MainClientExec implements ClientExecChain {

    private final Log log = LogFactory.getLog(getClass());

    private final HttpRequestExecutor requestExecutor;
    private final HttpClientConnectionManager connManager;
    private final ConnectionReuseStrategy reuseStrategy;
    private final ConnectionKeepAliveStrategy keepAliveStrategy;
    private final HttpProcessor proxyHttpProcessor;
    private final AuthenticationStrategy targetAuthStrategy;
    private final AuthenticationStrategy proxyAuthStrategy;
    private final HttpAuthenticator authenticator;
    private final UserTokenHandler userTokenHandler;
    private final HttpRouteDirector routeDirector;

    /**
     * @since 4.4
     */
    public MainClientExec(
            final HttpRequestExecutor requestExecutor,
            final HttpClientConnectionManager connManager,
            final ConnectionReuseStrategy reuseStrategy,
            final ConnectionKeepAliveStrategy keepAliveStrategy,
            final HttpProcessor proxyHttpProcessor,
            final AuthenticationStrategy targetAuthStrategy,
            final AuthenticationStrategy proxyAuthStrategy,
            final UserTokenHandler userTokenHandler) {
        Args.notNull(requestExecutor, "HTTP request executor");
        Args.notNull(connManager, "Client connection manager");
        Args.notNull(reuseStrategy, "Connection reuse strategy");
        Args.notNull(keepAliveStrategy, "Connection keep alive strategy");
        Args.notNull(proxyHttpProcessor, "Proxy HTTP processor");
        Args.notNull(targetAuthStrategy, "Target authentication strategy");
        Args.notNull(proxyAuthStrategy, "Proxy authentication strategy");
        Args.notNull(userTokenHandler, "User token handler");
        this.authenticator      = new HttpAuthenticator();
        this.routeDirector      = new BasicRouteDirector();
        // 请求执行器
        this.requestExecutor    = requestExecutor;
        // 连接池
        this.connManager        = connManager;
        // 重用策略
        this.reuseStrategy      = reuseStrategy;
        // keepalive 策略
        this.keepAliveStrategy  = keepAliveStrategy;
        this.proxyHttpProcessor = proxyHttpProcessor;
        // 目标主机的 认证 策略
        this.targetAuthStrategy = targetAuthStrategy;
        // 代理认证的 策略
        this.proxyAuthStrategy  = proxyAuthStrategy;
        this.userTokenHandler   = userTokenHandler;
    }

    public MainClientExec(
            final HttpRequestExecutor requestExecutor,
            final HttpClientConnectionManager connManager,
            final ConnectionReuseStrategy reuseStrategy,
            final ConnectionKeepAliveStrategy keepAliveStrategy,
            final AuthenticationStrategy targetAuthStrategy,
            final AuthenticationStrategy proxyAuthStrategy,
            final UserTokenHandler userTokenHandler) {
        this(requestExecutor, connManager, reuseStrategy, keepAliveStrategy,
                new ImmutableHttpProcessor(new RequestTargetHost()),
                targetAuthStrategy, proxyAuthStrategy, userTokenHandler);
    }
    // 真正执行 请求的地方
    @Override
    public CloseableHttpResponse execute(
            final HttpRoute route,
            final HttpRequestWrapper request,
            final HttpClientContext context,
            final HttpExecutionAware execAware) throws IOException, HttpException {
        Args.notNull(route, "HTTP route");
        Args.notNull(request, "HTTP request");
        Args.notNull(context, "HTTP context");
        // 目标主机认证状态
        AuthState targetAuthState = context.getTargetAuthState();
        if (targetAuthState == null) {
            targetAuthState = new AuthState();
            context.setAttribute(HttpClientContext.TARGET_AUTH_STATE, targetAuthState);
        }
        // 代理主机的认证状态
        AuthState proxyAuthState = context.getProxyAuthState();
        if (proxyAuthState == null) {
            proxyAuthState = new AuthState();
            context.setAttribute(HttpClientContext.PROXY_AUTH_STATE, proxyAuthState);
        }
        // 对httpEntity 进行一些加强
        if (request instanceof HttpEntityEnclosingRequest) {
            RequestEntityProxy.enhance((HttpEntityEnclosingRequest) request);
        }
        // 获取 http.user-token
        Object userToken = context.getUserToken();
        // 从pool中 获取一个 连接,即socket 连接
        // 获取连接,此返回一个  future
        // --- 重点 ----  去连接池中请求一个连接
        final ConnectionRequest connRequest = connManager.requestConnection(route, userToken);
        if (execAware != null) {
            if (execAware.isAborted()) {
                connRequest.cancel();
                throw new RequestAbortedException("Request aborted");
            }
            execAware.setCancellable(connRequest);
        }
        // 获取 requestConfig
        final RequestConfig config = context.getRequestConfig();

        final HttpClientConnection managedConn;
        try {
            // 获取连接超时时间
            final int timeout = config.getConnectionRequestTimeout();
            // 真正获取连接的地方
            // 获取刚刚请求的连接
            managedConn = connRequest.get(timeout > 0 ? timeout : 0, TimeUnit.MILLISECONDS);
        } catch(final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RequestAbortedException("Request aborted", interrupted);
        } catch(final ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause == null) {
                cause = ex;
            }
            throw new RequestAbortedException("Request execution failed", cause);
        }
        // 记录连接
        context.setAttribute(HttpCoreContext.HTTP_CONNECTION, managedConn);

        if (config.isStaleConnectionCheckEnabled()) {
            // validate connection
            // 有socket 连接了,就表示有 open了
            if (managedConn.isOpen()) {
                this.log.debug("Stale connection check");
                // 有socket连接了,就为stale,那么就关闭
                if (managedConn.isStale()) {
                    this.log.debug("Stale connection detected");
                    managedConn.close();
                }
            }
        }
        // 创建一个 连接 holder
        final ConnectionHolder connHolder = new ConnectionHolder(this.log, this.connManager, managedConn);
        try {
            if (execAware != null) {
                execAware.setCancellable(connHolder);
            }

            HttpResponse response;
            // 可以看到这里是一个 死循环哦, 来执行具体的请求
            // 重点 - -----------
            for (int execCount = 1;; execCount++) {
                // 执行过一次, 且request不可重复,则抛出错误
                if (execCount > 1 && !RequestEntityProxy.isRepeatable(request)) {
                    throw new NonRepeatableRequestException("Cannot retry request " +
                            "with a non-repeatable request entity.");
                }

                if (execAware != null && execAware.isAborted()) {
                    throw new RequestAbortedException("Request aborted");
                }
                // 当前没有连接,则创建连接
                if (!managedConn.isOpen()) {
                    this.log.debug("Opening connection " + route);
                    try {
                        // 建立一条到目的主机的 route,真正创建socket 连接
                        // ----- 重点 -------------
                        establishRoute(proxyAuthState, managedConn, route, request, context);
                    } catch (final TunnelRefusedException ex) {
                        if (this.log.isDebugEnabled()) {
                            this.log.debug(ex.getMessage());
                        }
                        response = ex.getResponse();
                        // 发生异常,则退出循环
                        break;
                    }
                }
                // socket timeout 进行配置
                final int timeout = config.getSocketTimeout();
                // 设置 socketTimeout时间
                if (timeout >= 0) {
                    managedConn.setSocketTimeout(timeout);
                }

                if (execAware != null && execAware.isAborted()) {
                    throw new RequestAbortedException("Request aborted");
                }

                if (this.log.isDebugEnabled()) {
                    this.log.debug("Executing request " + request.getRequestLine());
                }
                // 如果没有 Authorization 请求头
                if (!request.containsHeader(AUTH.WWW_AUTH_RESP)) {
                    if (this.log.isDebugEnabled()) {
                        this.log.debug("Target auth state: " + targetAuthState.getState());
                    }
                    this.authenticator.generateAuthResponse(request, targetAuthState, context);
                }
                // 没有请求Proxy-Authorization  且没 route 不是 tunnelled
                if (!request.containsHeader(AUTH.PROXY_AUTH_RESP) && !route.isTunnelled()) {
                    if (this.log.isDebugEnabled()) {
                        this.log.debug("Proxy auth state: " + proxyAuthState.getState());
                    }
                    this.authenticator.generateAuthResponse(request, proxyAuthState, context);
                }
                // 记录 http-request到context中
                context.setAttribute(HttpCoreContext.HTTP_REQUEST, request);
                // 执行请求,并得到请求体
                // 开始执行请求
                // ---- 重点 --------
                response = requestExecutor.execute(request, managedConn, context);

                // The connection is in or can be brought to a re-usable state.
                // keepalive的策略
                if (reuseStrategy.keepAlive(response, context)) {
                    // Set the idle duration of this connection
                    // 获取 timeout 的值
                    final long duration = keepAliveStrategy.getKeepAliveDuration(response, context);
                    if (this.log.isDebugEnabled()) {
                        final String s;
                        if (duration > 0) {
                            s = "for " + duration + " " + TimeUnit.MILLISECONDS;
                        } else {
                            s = "indefinitely";
                        }
                        this.log.debug("Connection can be kept alive " + s);
                    }
                    // 为连接设置过期时间
                    connHolder.setValidFor(duration, TimeUnit.MILLISECONDS);
                    //  设置可重用的标志
                    connHolder.markReusable();
                } else {
                    // 否则标记为 不可重用
                    connHolder.markNonReusable();
                }
                // 如果需要认真
                if (needAuthentication(
                        targetAuthState, proxyAuthState, route, response, context)) {
                    // Make sure the response body is fully consumed, if present
                    final HttpEntity entity = response.getEntity();
                    // 如果连接重用,则关闭entity流
                    if (connHolder.isReusable()) {
                        // 关闭 entity对应流
                        EntityUtils.consume(entity);
                    } else {
                        // 连接不可重用,则关闭连接
                        managedConn.close();
                        // 如果代理认证状态Wie success
                        if (proxyAuthState.getState() == AuthProtocolState.SUCCESS
                                && proxyAuthState.isConnectionBased()) {
                            this.log.debug("Resetting proxy auth state");
                            proxyAuthState.reset();
                        }
                        // 如果 target 认证状态为 success
                        if (targetAuthState.getState() == AuthProtocolState.SUCCESS
                                && targetAuthState.isConnectionBased()) {
                            this.log.debug("Resetting target auth state");
                            targetAuthState.reset();
                        }
                    }
                    // discard previous auth headers
                    // 得到原来的请求
                    // 移除认证的header
                    final HttpRequest original = request.getOriginal();
                    if (!original.containsHeader(AUTH.WWW_AUTH_RESP)) {
                        request.removeHeaders(AUTH.WWW_AUTH_RESP);
                    }
                    if (!original.containsHeader(AUTH.PROXY_AUTH_RESP)) {
                        request.removeHeaders(AUTH.PROXY_AUTH_RESP);
                    }
                } else {
                    break;
                }
            }
            // userToken的获取
            if (userToken == null) {
                userToken = userTokenHandler.getUserToken(context);
                context.setAttribute(HttpClientContext.USER_TOKEN, userToken);
            }
            // 设置 state
            // 此就是 连接的 state
            if (userToken != null) {
                connHolder.setState(userToken);
            }

            // check for entity, release connection if possible
            // 获取响应体
            final HttpEntity entity = response.getEntity();
            // 如果没有响应体,或者响应不是是 streaming,则回收连接
            if (entity == null || !entity.isStreaming()) {
                // connection not needed and (assumed to be) in re-usable state
                // 回收连接
                connHolder.releaseConnection();
                return new HttpResponseProxy(response, null);
            }
            // 返回响应
            return new HttpResponseProxy(response, connHolder);
        } catch (final ConnectionShutdownException ex) {
            final InterruptedIOException ioex = new InterruptedIOException(
                    "Connection has been shut down");
            ioex.initCause(ex);
            throw ioex;
        } catch (final HttpException ex) {
            // 中止 连接
            connHolder.abortConnection();
            throw ex;
        } catch (final IOException ex) {
            // 中止连接
            connHolder.abortConnection();
            // 认证装填复位
            if (proxyAuthState.isConnectionBased()) {
                proxyAuthState.reset();
            }
            if (targetAuthState.isConnectionBased()) {
                targetAuthState.reset();
            }
            throw ex;
        } catch (final RuntimeException ex) {
            // 中止连接,并复位认证状态
            connHolder.abortConnection();
            if (proxyAuthState.isConnectionBased()) {
                proxyAuthState.reset();
            }
            if (targetAuthState.isConnectionBased()) {
                targetAuthState.reset();
            }
            throw ex;
        } catch (final Error error) {
            connManager.shutdown();
            throw error;
        }
    }

    /**
     * Establishes the target route.
     */
    // 建立route
    void establishRoute(
            final AuthState proxyAuthState,
            final HttpClientConnection managedConn,
            final HttpRoute route,
            final HttpRequest request,
            final HttpClientContext context) throws HttpException, IOException {
        // 获取 requestConfig
        final RequestConfig config = context.getRequestConfig();
        //  connectionTimeout
        final int timeout = config.getConnectTimeout();
        // 记录
        final RouteTracker tracker = new RouteTracker(route);
        int step;
        do {
            final HttpRoute fact = tracker.toRoute();
            step = this.routeDirector.nextStep(route, fact);

            switch (step) {
            // 直接连接主机
            case HttpRouteDirector.CONNECT_TARGET:
                // 和目的主机 进行连接
                this.connManager.connect(
                        managedConn,
                        route,
                        timeout > 0 ? timeout : 0,
                        context);
                tracker.connectTarget(route.isSecure());
                break;
                // 连接代理
            case HttpRouteDirector.CONNECT_PROXY:
                this.connManager.connect(
                        managedConn,
                        route,
                        timeout > 0 ? timeout : 0,
                        context);
                final HttpHost proxy  = route.getProxyHost();
                tracker.connectProxy(proxy, route.isSecure() && !route.isTunnelled());
                break;
            case HttpRouteDirector.TUNNEL_TARGET: {
                final boolean secure = createTunnelToTarget(
                        proxyAuthState, managedConn, route, request, context);
                this.log.debug("Tunnel to target created.");
                tracker.tunnelTarget(secure);
            }   break;

            case HttpRouteDirector.TUNNEL_PROXY: {
                // The most simple example for this case is a proxy chain
                // of two proxies, where P1 must be tunnelled to P2.
                // route: Source -> P1 -> P2 -> Target (3 hops)
                // fact:  Source -> P1 -> Target       (2 hops)
                final int hop = fact.getHopCount()-1; // the hop to establish
                final boolean secure = createTunnelToProxy(route, hop, context);
                this.log.debug("Tunnel to proxy created.");
                tracker.tunnelProxy(route.getHopTarget(hop), secure);
            }   break;

            case HttpRouteDirector.LAYER_PROTOCOL:
                this.connManager.upgrade(managedConn, route, context);
                tracker.layerProtocol(route.isSecure());
                break;

            case HttpRouteDirector.UNREACHABLE:
                // 出错, 主机不可达
                throw new HttpException("Unable to establish route: " +
                        "planned = " + route + "; current = " + fact);
            case HttpRouteDirector.COMPLETE:
                // 标记连接完成
                this.connManager.routeComplete(managedConn, route, context);
                break;
            default:
                throw new IllegalStateException("Unknown step indicator "
                        + step + " from RouteDirector.");
            }

        } while (step > HttpRouteDirector.COMPLETE);
    }

    /**
     * Creates a tunnel to the target server.
     * The connection must be established to the (last) proxy.
     * A CONNECT request for tunnelling through the proxy will
     * be created and sent, the response received and checked.
     * This method does <i>not</i> update the connection with
     * information about the tunnel, that is left to the caller.
     */
    private boolean createTunnelToTarget(
            final AuthState proxyAuthState,
            final HttpClientConnection managedConn,
            final HttpRoute route,
            final HttpRequest request,
            final HttpClientContext context) throws HttpException, IOException {

        final RequestConfig config = context.getRequestConfig();
        final int timeout = config.getConnectTimeout();

        final HttpHost target = route.getTargetHost();
        final HttpHost proxy = route.getProxyHost();
        HttpResponse response = null;

        final String authority = target.toHostString();
        final HttpRequest connect = new BasicHttpRequest("CONNECT", authority, request.getProtocolVersion());

        this.requestExecutor.preProcess(connect, this.proxyHttpProcessor, context);

        while (response == null) {
            if (!managedConn.isOpen()) {
                this.connManager.connect(
                        managedConn,
                        route,
                        timeout > 0 ? timeout : 0,
                        context);
            }

            connect.removeHeaders(AUTH.PROXY_AUTH_RESP);
            this.authenticator.generateAuthResponse(connect, proxyAuthState, context);

            response = this.requestExecutor.execute(connect, managedConn, context);
            this.requestExecutor.postProcess(response, this.proxyHttpProcessor, context);

            final int status = response.getStatusLine().getStatusCode();
            if (status < 200) {
                throw new HttpException("Unexpected response to CONNECT request: " +
                        response.getStatusLine());
            }

            if (config.isAuthenticationEnabled()) {
                if (this.authenticator.isAuthenticationRequested(proxy, response,
                        this.proxyAuthStrategy, proxyAuthState, context)) {
                    if (this.authenticator.handleAuthChallenge(proxy, response,
                            this.proxyAuthStrategy, proxyAuthState, context)) {
                        // Retry request
                        if (this.reuseStrategy.keepAlive(response, context)) {
                            this.log.debug("Connection kept alive");
                            // Consume response content
                            final HttpEntity entity = response.getEntity();
                            EntityUtils.consume(entity);
                        } else {
                            managedConn.close();
                        }
                        response = null;
                    }
                }
            }
        }

        final int status = response.getStatusLine().getStatusCode();

        if (status > 299) {

            // Buffer response content
            final HttpEntity entity = response.getEntity();
            if (entity != null) {
                response.setEntity(new BufferedHttpEntity(entity));
            }

            managedConn.close();
            throw new TunnelRefusedException("CONNECT refused by proxy: " +
                    response.getStatusLine(), response);
        }

        // How to decide on security of the tunnelled connection?
        // The socket factory knows only about the segment to the proxy.
        // Even if that is secure, the hop to the target may be insecure.
        // Leave it to derived classes, consider insecure by default here.
        return false;
    }

    /**
     * Creates a tunnel to an intermediate proxy.
     * This method is <i>not</i> implemented in this class.
     * It just throws an exception here.
     */
    private boolean createTunnelToProxy(
            final HttpRoute route,
            final int hop,
            final HttpClientContext context) throws HttpException {

        // Have a look at createTunnelToTarget and replicate the parts
        // you need in a custom derived class. If your proxies don't require
        // authentication, it is not too hard. But for the stock version of
        // HttpClient, we cannot make such simplifying assumptions and would
        // have to include proxy authentication code. The HttpComponents team
        // is currently not in a position to support rarely used code of this
        // complexity. Feel free to submit patches that refactor the code in
        // createTunnelToTarget to facilitate re-use for proxy tunnelling.

        throw new HttpException("Proxy chains are not supported.");
    }

    private boolean needAuthentication(
            final AuthState targetAuthState,
            final AuthState proxyAuthState,
            final HttpRoute route,
            final HttpResponse response,
            final HttpClientContext context) {
        final RequestConfig config = context.getRequestConfig();
        if (config.isAuthenticationEnabled()) {
            HttpHost target = context.getTargetHost();
            if (target == null) {
                target = route.getTargetHost();
            }
            if (target.getPort() < 0) {
                target = new HttpHost(
                        target.getHostName(),
                        route.getTargetHost().getPort(),
                        target.getSchemeName());
            }
            final boolean targetAuthRequested = this.authenticator.isAuthenticationRequested(
                    target, response, this.targetAuthStrategy, targetAuthState, context);

            HttpHost proxy = route.getProxyHost();
            // if proxy is not set use target host instead
            if (proxy == null) {
                proxy = route.getTargetHost();
            }
            final boolean proxyAuthRequested = this.authenticator.isAuthenticationRequested(
                    proxy, response, this.proxyAuthStrategy, proxyAuthState, context);

            if (targetAuthRequested) {
                return this.authenticator.handleAuthChallenge(target, response,
                        this.targetAuthStrategy, targetAuthState, context);
            }
            if (proxyAuthRequested) {
                return this.authenticator.handleAuthChallenge(proxy, response,
                        this.proxyAuthStrategy, proxyAuthState, context);
            }
        }
        return false;
    }

}
