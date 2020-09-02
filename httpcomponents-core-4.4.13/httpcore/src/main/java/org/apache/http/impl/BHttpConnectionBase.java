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

package org.apache.http.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.http.ConnectionClosedException;
import org.apache.http.Header;
import org.apache.http.HttpConnectionMetrics;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpInetConnection;
import org.apache.http.HttpMessage;
import org.apache.http.config.MessageConstraints;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.entity.ContentLengthStrategy;
import org.apache.http.impl.entity.LaxContentLengthStrategy;
import org.apache.http.impl.entity.StrictContentLengthStrategy;
import org.apache.http.impl.io.ChunkedInputStream;
import org.apache.http.impl.io.ChunkedOutputStream;
import org.apache.http.impl.io.ContentLengthInputStream;
import org.apache.http.impl.io.ContentLengthOutputStream;
import org.apache.http.impl.io.EmptyInputStream;
import org.apache.http.impl.io.HttpTransportMetricsImpl;
import org.apache.http.impl.io.IdentityInputStream;
import org.apache.http.impl.io.IdentityOutputStream;
import org.apache.http.impl.io.SessionInputBufferImpl;
import org.apache.http.impl.io.SessionOutputBufferImpl;
import org.apache.http.io.SessionInputBuffer;
import org.apache.http.io.SessionOutputBuffer;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.Args;
import org.apache.http.util.NetUtils;

/**
 * This class serves as a base for all {@link org.apache.http.HttpConnection} implementations
 * and provides functionality common to both client and server HTTP connections.
 *
 * @since 4.0
 */
public class BHttpConnectionBase implements HttpInetConnection {
    // 输入流以及缓冲区
    private final SessionInputBufferImpl inBuffer;
    // 输出流 以及 缓冲区
    private final SessionOutputBufferImpl outbuffer;
    //
    private final MessageConstraints messageConstraints;
    private final HttpConnectionMetricsImpl connMetrics;
    private final ContentLengthStrategy incomingContentStrategy;
    private final ContentLengthStrategy outgoingContentStrategy;
    // 保存socket连接
    private final AtomicReference<Socket> socketHolder;

    /**
     * Creates new instance of BHttpConnectionBase.
     *
     * @param bufferSize buffer size. Must be a positive number.
     * @param fragmentSizeHint fragment size hint.
     * @param charDecoder decoder to be used for decoding HTTP protocol elements.
     *   If {@code null} simple type cast will be used for byte to char conversion.
     * @param charEncoder encoder to be used for encoding HTTP protocol elements.
     *   If {@code null} simple type cast will be used for char to byte conversion.
     * @param messageConstraints Message constraints. If {@code null}
     *   {@link MessageConstraints#DEFAULT} will be used.
     * @param incomingContentStrategy incoming content length strategy. If {@code null}
     *   {@link LaxContentLengthStrategy#INSTANCE} will be used.
     * @param outgoingContentStrategy outgoing content length strategy. If {@code null}
     *   {@link StrictContentLengthStrategy#INSTANCE} will be used.
     */
    protected BHttpConnectionBase(
            final int bufferSize,
            final int fragmentSizeHint,
            final CharsetDecoder charDecoder,
            final CharsetEncoder charEncoder,
            final MessageConstraints messageConstraints,
            final ContentLengthStrategy incomingContentStrategy,
            final ContentLengthStrategy outgoingContentStrategy) {
        super();
        Args.positive(bufferSize, "Buffer size");
        // 数据监测 用途
        final HttpTransportMetricsImpl inTransportMetrics = new HttpTransportMetricsImpl();
        final HttpTransportMetricsImpl outTransportMetrics = new HttpTransportMetricsImpl();
        // 输入流时 SessionInputBufferImpl, 且可以指定编码
        this.inBuffer = new SessionInputBufferImpl(inTransportMetrics, bufferSize, -1,
                messageConstraints != null ? messageConstraints : MessageConstraints.DEFAULT, charDecoder);
        // 输出流  都是SessionInputBufferImpl
        this.outbuffer = new SessionOutputBufferImpl(outTransportMetrics, bufferSize, fragmentSizeHint,
                charEncoder);
        this.messageConstraints = messageConstraints;
        // 连接的 数据监测
        this.connMetrics = new HttpConnectionMetricsImpl(inTransportMetrics, outTransportMetrics);
        // 输入内容的策略
        this.incomingContentStrategy = incomingContentStrategy != null ? incomingContentStrategy :
            LaxContentLengthStrategy.INSTANCE;
        // 输出内容的策略
        this.outgoingContentStrategy = outgoingContentStrategy != null ? outgoingContentStrategy :
            StrictContentLengthStrategy.INSTANCE;
        // 这是一个 保存socket 连接的 引用
        this.socketHolder = new AtomicReference<Socket>();
    }
    // 输入输出buffer 绑定 到输入输出流
    protected void ensureOpen() throws IOException {
        // 获取socket
        final Socket socket = this.socketHolder.get();
        // 如果还没有创建 连接,则报错
        if (socket == null) {
            throw new ConnectionClosedException();
        }
        // 绑定输入 buffer
        if (!this.inBuffer.isBound()) {
            this.inBuffer.bind(getSocketInputStream(socket));
        }
        // 输出buffer 是否绑定了 socket的stream
        if (!this.outbuffer.isBound()) {
            // 没有绑定 则进行绑定
            this.outbuffer.bind(getSocketOutputStream(socket));
        }
    }

    protected InputStream getSocketInputStream(final Socket socket) throws IOException {
        return socket.getInputStream();
    }

    protected OutputStream getSocketOutputStream(final Socket socket) throws IOException {
        return socket.getOutputStream();
    }

    /**
     * Binds this connection to the given {@link Socket}. This socket will be
     * used by the connection to send and receive data.
     * <p>
     * After this method's execution the connection status will be reported
     * as open and the {@link #isOpen()} will return {@code true}.
     *
     * @param socket the socket.
     * @throws IOException in case of an I/O error.
     */
    protected void bind(final Socket socket) throws IOException {
        Args.notNull(socket, "Socket");
        this.socketHolder.set(socket);
        this.inBuffer.bind(null);
        this.outbuffer.bind(null);
    }

    protected SessionInputBuffer getSessionInputBuffer() {
        return this.inBuffer;
    }

    protected SessionOutputBuffer getSessionOutputBuffer() {
        return this.outbuffer;
    }
    // flush操作
    // 即把buffer中的消息写出
    protected void doFlush() throws IOException {
        this.outbuffer.flush();
    }

    @Override
    public boolean isOpen() {
        return this.socketHolder.get() != null;
    }

    protected Socket getSocket() {
        return this.socketHolder.get();
    }
    // 创建输出流
    // 注入哦 这里的 outBuffer 就是输出缓存区
    protected OutputStream createOutputStream(
            final long len,
            final SessionOutputBuffer outbuffer) {
        // 如果长度为  -2 ,则是 chunked 流
        if (len == ContentLengthStrategy.CHUNKED) {
            return new ChunkedOutputStream(2048, outbuffer);
            // 如果长度为 -1,则创建 IdentityOutputStream 流
        } else if (len == ContentLengthStrategy.IDENTITY) {
            return new IdentityOutputStream(outbuffer);
        } else {
            // 否则创建 ContentLengthOutputStream
            return new ContentLengthOutputStream(outbuffer, len);
        }
    }
    // 准备输出
    protected OutputStream prepareOutput(final HttpMessage message) throws HttpException {
        // 获取要 输出的消息的长度
        final long len = this.outgoingContentStrategy.determineLength(message);
        // 即 其会把 len长度的数据 写到 outbuffer中
        // 注意这里的 outbuffer 和socket 输出流绑定的
        return createOutputStream(len, this.outbuffer);
    }
    // 创建一个输入流
    protected InputStream createInputStream(
            final long len,
            final SessionInputBuffer inBuffer) {
        if (len == ContentLengthStrategy.CHUNKED) {
            return new ChunkedInputStream(inBuffer, this.messageConstraints);
        } else if (len == ContentLengthStrategy.IDENTITY) {
            return new IdentityInputStream(inBuffer);
        } else if (len == 0L) {
            return EmptyInputStream.INSTANCE;
        } else {
            return new ContentLengthInputStream(inBuffer, len);
        }
    }
    // 解析请求体
    protected HttpEntity prepareInput(final HttpMessage message) throws HttpException {
        // 先创建一个请求体
        final BasicHttpEntity entity = new BasicHttpEntity();
        // 得到 长度
        final long len = this.incomingContentStrategy.determineLength(message);
        // 创建输入流
        // 根据不同的情况 创建输入流 来进行数据读取
        final InputStream inStream = createInputStream(len, this.inBuffer);
        if (len == ContentLengthStrategy.CHUNKED) {
            entity.setChunked(true);
            entity.setContentLength(-1);
            entity.setContent(inStream);
        } else if (len == ContentLengthStrategy.IDENTITY) {
            entity.setChunked(false);
            entity.setContentLength(-1);
            entity.setContent(inStream);
        } else {
            entity.setChunked(false);
            entity.setContentLength(len);
            entity.setContent(inStream);
        }
        //  获取 Content-Type
        final Header contentTypeHeader = message.getFirstHeader(HTTP.CONTENT_TYPE);
        if (contentTypeHeader != null) {
            entity.setContentType(contentTypeHeader);
        }
        // 获取 Content-Encoding 请求头
        final Header contentEncodingHeader = message.getFirstHeader(HTTP.CONTENT_ENCODING);
        if (contentEncodingHeader != null) {
            entity.setContentEncoding(contentEncodingHeader);
        }
        return entity;
    }

    @Override
    public InetAddress getLocalAddress() {
        final Socket socket = this.socketHolder.get();
        return socket != null ? socket.getLocalAddress() : null;
    }

    @Override
    public int getLocalPort() {
        final Socket socket = this.socketHolder.get();
        return socket != null ? socket.getLocalPort() : -1;
    }

    @Override
    public InetAddress getRemoteAddress() {
        final Socket socket = this.socketHolder.get();
        return socket != null ? socket.getInetAddress() : null;
    }

    @Override
    public int getRemotePort() {
        final Socket socket = this.socketHolder.get();
        return socket != null ? socket.getPort() : -1;
    }

    @Override
    public void setSocketTimeout(final int timeout) {
        final Socket socket = this.socketHolder.get();
        if (socket != null) {
            try {
                socket.setSoTimeout(timeout);
            } catch (final SocketException ignore) {
                // It is not quite clear from the Sun's documentation if there are any
                // other legitimate cases for a socket exception to be thrown when setting
                // SO_TIMEOUT besides the socket being already closed
            }
        }
    }

    @Override
    public int getSocketTimeout() {
        final Socket socket = this.socketHolder.get();
        if (socket != null) {
            try {
                return socket.getSoTimeout();
            } catch (final SocketException ignore) {
                return -1;
            }
        }
        return -1;
    }

    @Override
    public void shutdown() throws IOException {
        final Socket socket = this.socketHolder.getAndSet(null);
        if (socket != null) {
            // force abortive close (RST)
            try {
                socket.setSoLinger(true, 0);
            } catch (final IOException ex) {
            } finally {
                socket.close();
            }
        }
    }
    // 关闭连接
    @Override
    public void close() throws IOException {
        // 释放引用中的 socket
        final Socket socket = this.socketHolder.getAndSet(null);
        if (socket != null) {
            try {
                // 情况输入buffer
                this.inBuffer.clear();
                // flush 输出buffer
                this.outbuffer.flush();
                try {
                    try {
                        // 关闭输出
                        socket.shutdownOutput();
                    } catch (final IOException ignore) {
                    }
                    try {
                        // 关闭输入
                        socket.shutdownInput();
                    } catch (final IOException ignore) {
                    }
                } catch (final UnsupportedOperationException ignore) {
                    // if one isn't supported, the other one isn't either
                }
            } finally {
                // 关闭socket
                socket.close();
            }
        }
    }
    // 设置超时时间 去读取数据
    private int fillInputBuffer(final int timeout) throws IOException {
        final Socket socket = this.socketHolder.get();
        final int oldtimeout = socket.getSoTimeout();
        try {
            // 表示等待输入流数据超时时间
            // 设置读取数据的超时时间
            socket.setSoTimeout(timeout);
            return this.inBuffer.fillBuffer();
        } finally {
            // 最后把 时间设置回来
            socket.setSoTimeout(oldtimeout);
        }
    }
    // 在规定的时间从输入流中读取数据
    protected boolean awaitInput(final int timeout) throws IOException {
        // 如果输入缓存中有数据  则读取数据
        if (this.inBuffer.hasBufferedData()) {
            return true;
        }
        // 从输入流中 读取数据
        fillInputBuffer(timeout);
        // 在判断 输入缓存中是否有数据
        return this.inBuffer.hasBufferedData();
    }

    @Override
    public boolean isStale() {
        if (!isOpen()) {
            return true;
        }
        try {
            final int bytesRead = fillInputBuffer(1);
            return bytesRead < 0;
        } catch (final SocketTimeoutException ex) {
            return false;
        } catch (final IOException ex) {
            return true;
        }
    }

    protected void incrementRequestCount() {
        this.connMetrics.incrementRequestCount();
    }

    protected void incrementResponseCount() {
        this.connMetrics.incrementResponseCount();
    }

    @Override
    public HttpConnectionMetrics getMetrics() {
        return this.connMetrics;
    }

    @Override
    public String toString() {
        final Socket socket = this.socketHolder.get();
        if (socket != null) {
            final StringBuilder buffer = new StringBuilder();
            final SocketAddress remoteAddress = socket.getRemoteSocketAddress();
            final SocketAddress localAddress = socket.getLocalSocketAddress();
            if (remoteAddress != null && localAddress != null) {
                NetUtils.formatAddress(buffer, localAddress);
                buffer.append("<->");
                NetUtils.formatAddress(buffer, remoteAddress);
            }
            return buffer.toString();
        }
        return "[Not bound]";
    }

}
