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
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;

import org.apache.http.HttpClientConnection;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.config.MessageConstraints;
import org.apache.http.entity.ContentLengthStrategy;
import org.apache.http.impl.io.DefaultHttpRequestWriterFactory;
import org.apache.http.impl.io.DefaultHttpResponseParserFactory;
import org.apache.http.io.HttpMessageParser;
import org.apache.http.io.HttpMessageParserFactory;
import org.apache.http.io.HttpMessageWriter;
import org.apache.http.io.HttpMessageWriterFactory;
import org.apache.http.util.Args;

/**
 * Default implementation of {@link HttpClientConnection}.
 *
 * @since 4.3
 */
public class DefaultBHttpClientConnection extends BHttpConnectionBase
                                                   implements HttpClientConnection {
    // 解析响应
    private final HttpMessageParser<HttpResponse> responseParser;
    // 输出 request
    private final HttpMessageWriter<HttpRequest> requestWriter;

    /**
     * Creates new instance of DefaultBHttpClientConnection.
     *
     * @param bufferSize buffer size. Must be a positive number.
     * @param fragmentSizeHint fragment size hint.
     * @param charDecoder decoder to be used for decoding HTTP protocol elements.
     *   If {@code null} simple type cast will be used for byte to char conversion.
     * @param charEncoder encoder to be used for encoding HTTP protocol elements.
     *   If {@code null} simple type cast will be used for char to byte conversion.
     * @param constraints Message constraints. If {@code null}
     *   {@link MessageConstraints#DEFAULT} will be used.
     * @param incomingContentStrategy incoming content length strategy. If {@code null}
     *   {@link org.apache.http.impl.entity.LaxContentLengthStrategy#INSTANCE} will be used.
     * @param outgoingContentStrategy outgoing content length strategy. If {@code null}
     *   {@link org.apache.http.impl.entity.StrictContentLengthStrategy#INSTANCE} will be used.
     * @param requestWriterFactory request writer factory. If {@code null}
     *   {@link DefaultHttpRequestWriterFactory#INSTANCE} will be used.
     * @param responseParserFactory response parser factory. If {@code null}
     *   {@link DefaultHttpResponseParserFactory#INSTANCE} will be used.
     */
    public DefaultBHttpClientConnection(
            final int bufferSize,
            final int fragmentSizeHint,
            final CharsetDecoder charDecoder,
            final CharsetEncoder charEncoder,
            final MessageConstraints constraints,
            final ContentLengthStrategy incomingContentStrategy,
            final ContentLengthStrategy outgoingContentStrategy,
            final HttpMessageWriterFactory<HttpRequest> requestWriterFactory,
            final HttpMessageParserFactory<HttpResponse> responseParserFactory) {
        super(bufferSize, fragmentSizeHint, charDecoder, charEncoder,
                constraints, incomingContentStrategy, outgoingContentStrategy);
        // 此是吧 request写入到 buffer中
        this.requestWriter = (requestWriterFactory != null ? requestWriterFactory :
            DefaultHttpRequestWriterFactory.INSTANCE).create(getSessionOutputBuffer());
        // 响应解析器
        this.responseParser = (responseParserFactory != null ? responseParserFactory :
            DefaultHttpResponseParserFactory.INSTANCE).create(getSessionInputBuffer(), constraints);
    }

    public DefaultBHttpClientConnection(
            final int bufferSize,
            final CharsetDecoder charDecoder,
            final CharsetEncoder charEncoder,
            final MessageConstraints constraints) {
        this(bufferSize, bufferSize, charDecoder, charEncoder, constraints, null, null, null, null);
    }

    public DefaultBHttpClientConnection(final int bufferSize) {
        this(bufferSize, bufferSize, null, null, null, null, null, null, null);
    }

    protected void onResponseReceived(final HttpResponse response) {
    }

    protected void onRequestSubmitted(final HttpRequest request) {
    }

    @Override
    public void bind(final Socket socket) throws IOException {
        super.bind(socket);
    }
    // 等待一个时间 获取响应
    @Override
    public boolean isResponseAvailable(final int timeout) throws IOException {
        // 确定输入输出buffer 绑定到了 输入输出流中
        ensureOpen();
        try {
            // 等待一段时间从输入流中读取数据
            return awaitInput(timeout);
        } catch (final SocketTimeoutException ex) {
            return false;
        }
    }
    // 发送请求行 到输出缓存中
    @Override
    public void sendRequestHeader(final HttpRequest request)
            throws HttpException, IOException {
        Args.notNull(request, "HTTP request");
        // 1. 确认流已经打开了
        ensureOpen();
        // 2. 把request写入到 buffer中
        this.requestWriter.write(request);
        // 扩展方法
        onRequestSubmitted(request);
        // 3. 增加 输出
        incrementRequestCount();
    }
    // 发送请求体
    @Override
    public void sendRequestEntity(final HttpEntityEnclosingRequest request)
            throws HttpException, IOException {
        Args.notNull(request, "HTTP request");
        // 保证流已经打开, 并进行了绑定
        ensureOpen();
        // 获取请求体
        final HttpEntity entity = request.getEntity();
        if (entity == null) {
            return;
        }
        //
        final OutputStream outStream = prepareOutput(request);
        // 把entity 数据写入到  outStream中
        // 此处的outStream 就是输出流
        entity.writeTo(outStream);
        outStream.close();
    }
    // 接收响应头
    @Override
    public HttpResponse receiveResponseHeader() throws HttpException, IOException {
        // 保证流已经打开
        ensureOpen();
        // 解析请求行  和  请求头信息
        final HttpResponse response = this.responseParser.parse();
        // 扩展方法
        onResponseReceived(response);
        if (response.getStatusLine().getStatusCode() >= HttpStatus.SC_OK) {
            // 响应数 增加
            incrementResponseCount();
        }
        return response;
    }

    @Override
    public void receiveResponseEntity(
            final HttpResponse response) throws HttpException, IOException {
        Args.notNull(response, "HTTP response");
        ensureOpen();
        // 解析请求体
        // 什么请求体只能解析一次? 因为请求是 直接使用的是 inputStream,当读取完成后,就直接关闭了,也就不能再进行读取了
        final HttpEntity entity = prepareInput(response);
        // 记录 entity
        response.setEntity(entity);
    }
    // 把缓存和 stream 进行flush 操作
    @Override
    public void flush() throws IOException {
        ensureOpen();
        doFlush();
    }

}
