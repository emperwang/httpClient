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
package org.apache.http.impl.conn;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHost;
import org.apache.http.annotation.Contract;
import org.apache.http.annotation.ThreadingBehavior;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.Lookup;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.HttpClientConnectionOperator;
import org.apache.http.conn.HttpHostConnectException;
import org.apache.http.conn.ManagedHttpClientConnection;
import org.apache.http.conn.SchemePortResolver;
import org.apache.http.conn.UnsupportedSchemeException;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.LayeredConnectionSocketFactory;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.Args;

/**
 * Default implementation of {@link HttpClientConnectionOperator} used as default in Http client,
 * when no instance provided by user to {@link BasicHttpClientConnectionManager} or {@link
 * PoolingHttpClientConnectionManager} constructor.
 *
 * @since 4.4
 */
@Contract(threading = ThreadingBehavior.IMMUTABLE_CONDITIONAL)
public class DefaultHttpClientConnectionOperator implements HttpClientConnectionOperator {

    static final String SOCKET_FACTORY_REGISTRY = "http.socket-factory-registry";

    private final Log log = LogFactory.getLog(getClass());

    private final Lookup<ConnectionSocketFactory> socketFactoryRegistry;
    private final SchemePortResolver schemePortResolver;
    private final DnsResolver dnsResolver;

    public DefaultHttpClientConnectionOperator(
            final Lookup<ConnectionSocketFactory> socketFactoryRegistry,
            final SchemePortResolver schemePortResolver,
            final DnsResolver dnsResolver) {
        super();
        Args.notNull(socketFactoryRegistry, "Socket factory registry");
        // 记录注册器
        this.socketFactoryRegistry = socketFactoryRegistry;
        // scheme解析器 , 也就是根据是 http 还是 https来判断
        this.schemePortResolver = schemePortResolver != null ? schemePortResolver :
            DefaultSchemePortResolver.INSTANCE;
        // dns解析
        this.dnsResolver = dnsResolver != null ? dnsResolver :
            SystemDefaultDnsResolver.INSTANCE;
    }
    // 获取 socketFactory的 registry
    @SuppressWarnings("unchecked")
    private Lookup<ConnectionSocketFactory> getSocketFactoryRegistry(final HttpContext context) {
        Lookup<ConnectionSocketFactory> reg = (Lookup<ConnectionSocketFactory>) context.getAttribute(
                SOCKET_FACTORY_REGISTRY);
        if (reg == null) {
            reg = this.socketFactoryRegistry;
        }
        return reg;
    }
    // 具体的连接创建
    @Override
    public void connect(
            final ManagedHttpClientConnection conn,
            final HttpHost host,
            final InetSocketAddress localAddress,
            final int connectTimeout,
            final SocketConfig socketConfig,
            final HttpContext context) throws IOException {
        // 1. 获取socket factory
        // 工厂类根据协议的不同有两种, http  和  https
        final Lookup<ConnectionSocketFactory> registry = getSocketFactoryRegistry(context);
        // 根据scheme是http 还是https来获取具体的socketfactory
        final ConnectionSocketFactory sf = registry.lookup(host.getSchemeName());
        // 如果没有找到 对应的工厂类,则抛出错误
        if (sf == null) {
            throw new UnsupportedSchemeException(host.getSchemeName() +
                    " protocol is not supported");
        }
        // 2. 解析主机名字,获取目的ip
        final InetAddress[] addresses = host.getAddress() != null ?
                new InetAddress[] { host.getAddress() } : this.dnsResolver.resolve(host.getHostName());
        // 2.1 解析目的主机的 port
        final int port = this.schemePortResolver.resolve(host);
        for (int i = 0; i < addresses.length; i++) {
            final InetAddress address = addresses[i];
            final boolean last = i == addresses.length - 1;
            // 3. 具体创建socket的操作
            Socket sock = sf.createSocket(context);
            // 4. 配置socket
            // 设置socket的超时
            sock.setSoTimeout(socketConfig.getSoTimeout());
            // 是否重用地址
            sock.setReuseAddress(socketConfig.isSoReuseAddress());
            // 设置 tcpNoDelay
            sock.setTcpNoDelay(socketConfig.isTcpNoDelay());
            // 设置是否 keepAlive
            sock.setKeepAlive(socketConfig.isSoKeepAlive());
            // 设置接收buffer
            if (socketConfig.getRcvBufSize() > 0) {
                sock.setReceiveBufferSize(socketConfig.getRcvBufSize());
            }
            // 设置发送buffer
            if (socketConfig.getSndBufSize() > 0) {
                sock.setSendBufferSize(socketConfig.getSndBufSize());
            }
            // 发送方socket被调用close之后，是否延迟关闭，继续发送数据。等待时间超时才关闭
            final int linger = socketConfig.getSoLinger();
            if (linger >= 0) {
                sock.setSoLinger(true, linger);
            }
            // 连接和 socket 绑定
            // 重点 ------------
            conn.bind(sock);
            // socket地址
            final InetSocketAddress remoteAddress = new InetSocketAddress(address, port);
            if (this.log.isDebugEnabled()) {
                this.log.debug("Connecting to " + remoteAddress);
            }
            try {
                // 5. 连接目的主机
                sock = sf.connectSocket(
                        connectTimeout, sock, host, remoteAddress, localAddress, context);
                // 6. 重新把 connection 和 socket 进行绑定
                conn.bind(sock);
                if (this.log.isDebugEnabled()) {
                    this.log.debug("Connection established " + conn);
                }
                return;
            } catch (final SocketTimeoutException ex) {
                if (last) {
                    throw new ConnectTimeoutException(ex, host, addresses);
                }
            } catch (final ConnectException ex) {
                if (last) {
                    final String msg = ex.getMessage();
                    throw "Connection timed out".equals(msg)
                                    ? new ConnectTimeoutException(ex, host, addresses)
                                    : new HttpHostConnectException(ex, host, addresses);
                }
            } catch (final NoRouteToHostException ex) {
                if (last) {
                    throw ex;
                }
            }
            if (this.log.isDebugEnabled()) {
                this.log.debug("Connect to " + remoteAddress + " timed out. " +
                        "Connection will be retried using another IP address");
            }
        }
    }

    @Override
    public void upgrade(
            final ManagedHttpClientConnection conn,
            final HttpHost host,
            final HttpContext context) throws IOException {
        final HttpClientContext clientContext = HttpClientContext.adapt(context);
        final Lookup<ConnectionSocketFactory> registry = getSocketFactoryRegistry(clientContext);
        final ConnectionSocketFactory sf = registry.lookup(host.getSchemeName());
        if (sf == null) {
            throw new UnsupportedSchemeException(host.getSchemeName() +
                    " protocol is not supported");
        }
        if (!(sf instanceof LayeredConnectionSocketFactory)) {
            throw new UnsupportedSchemeException(host.getSchemeName() +
                    " protocol does not support connection upgrade");
        }
        final LayeredConnectionSocketFactory lsf = (LayeredConnectionSocketFactory) sf;
        Socket sock = conn.getSocket();
        final int port = this.schemePortResolver.resolve(host);
        sock = lsf.createLayeredSocket(sock, host.getHostName(), port, context);
        conn.bind(sock);
    }

}
