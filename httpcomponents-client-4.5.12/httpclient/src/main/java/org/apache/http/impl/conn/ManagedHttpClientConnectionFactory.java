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

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.annotation.Contract;
import org.apache.http.annotation.ThreadingBehavior;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.conn.HttpConnectionFactory;
import org.apache.http.conn.ManagedHttpClientConnection;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.entity.ContentLengthStrategy;
import org.apache.http.impl.entity.LaxContentLengthStrategy;
import org.apache.http.impl.entity.StrictContentLengthStrategy;
import org.apache.http.impl.io.DefaultHttpRequestWriterFactory;
import org.apache.http.io.HttpMessageParserFactory;
import org.apache.http.io.HttpMessageWriterFactory;

/**
 * Factory for {@link ManagedHttpClientConnection} instances.
 * @since 4.3
 */
@Contract(threading = ThreadingBehavior.IMMUTABLE_CONDITIONAL)
public class ManagedHttpClientConnectionFactory
        implements HttpConnectionFactory<HttpRoute, ManagedHttpClientConnection> {
    // id生成器
    private static final AtomicLong COUNTER = new AtomicLong();
    // 饿汉式
    public static final ManagedHttpClientConnectionFactory INSTANCE = new ManagedHttpClientConnectionFactory();

    private final Log log = LogFactory.getLog(DefaultManagedHttpClientConnection.class);
    private final Log headerLog = LogFactory.getLog("org.apache.http.headers");
    private final Log wireLog = LogFactory.getLog("org.apache.http.wire");

    private final HttpMessageWriterFactory<HttpRequest> requestWriterFactory;
    private final HttpMessageParserFactory<HttpResponse> responseParserFactory;
    private final ContentLengthStrategy incomingContentStrategy;
    private final ContentLengthStrategy outgoingContentStrategy;

    /**
     * @since 4.4
     */
    public ManagedHttpClientConnectionFactory(
            final HttpMessageWriterFactory<HttpRequest> requestWriterFactory,
            final HttpMessageParserFactory<HttpResponse> responseParserFactory,
            final ContentLengthStrategy incomingContentStrategy,
            final ContentLengthStrategy outgoingContentStrategy) {
        super();
        // requestWriterFactory为DefaultHttpRequestWriterFactory默认值,即输出writer的类的工厂方法
        this.requestWriterFactory = requestWriterFactory != null ? requestWriterFactory :
                DefaultHttpRequestWriterFactory.INSTANCE;
        // 响应解析器的 功能方法
        this.responseParserFactory = responseParserFactory != null ? responseParserFactory :
                DefaultHttpResponseParserFactory.INSTANCE;
        // 输入内容的策略
        this.incomingContentStrategy = incomingContentStrategy != null ? incomingContentStrategy :
                LaxContentLengthStrategy.INSTANCE;
        // 输出内容的策略
        this.outgoingContentStrategy = outgoingContentStrategy != null ? outgoingContentStrategy :
                StrictContentLengthStrategy.INSTANCE;
    }

    public ManagedHttpClientConnectionFactory(
            final HttpMessageWriterFactory<HttpRequest> requestWriterFactory,
            final HttpMessageParserFactory<HttpResponse> responseParserFactory) {
        this(requestWriterFactory, responseParserFactory, null, null);
    }

    public ManagedHttpClientConnectionFactory(
            final HttpMessageParserFactory<HttpResponse> responseParserFactory) {
        this(null, responseParserFactory);
    }

    public ManagedHttpClientConnectionFactory() {
        this(null, null);
    }
    // 具体创建连接的地方
    @Override
    public ManagedHttpClientConnection create(final HttpRoute route, final ConnectionConfig config) {
        // 获取配置
        final ConnectionConfig cconfig = config != null ? config : ConnectionConfig.DEFAULT;
        // 解码器
        CharsetDecoder charDecoder = null;
        // 编码器
        CharsetEncoder charEncoder = null;
        final Charset charset = cconfig.getCharset();
        final CodingErrorAction malformedInputAction = cconfig.getMalformedInputAction() != null ?
                cconfig.getMalformedInputAction() : CodingErrorAction.REPORT;
        final CodingErrorAction unmappableInputAction = cconfig.getUnmappableInputAction() != null ?
                cconfig.getUnmappableInputAction() : CodingErrorAction.REPORT;
        // 如果有编码呢,则记录编码
        if (charset != null) {
            charDecoder = charset.newDecoder();
            charDecoder.onMalformedInput(malformedInputAction);
            charDecoder.onUnmappableCharacter(unmappableInputAction);
            charEncoder = charset.newEncoder();
            charEncoder.onMalformedInput(malformedInputAction);
            charEncoder.onUnmappableCharacter(unmappableInputAction);
        }
        final String id = "http-outgoing-" + Long.toString(COUNTER.getAndIncrement());
        // 生成一个 DefaultHttpRequestWriterFactory
        return new LoggingManagedHttpClientConnection(
                id,
                log,
                headerLog,
                wireLog,
                cconfig.getBufferSize(),
                cconfig.getFragmentSizeHint(),
                charDecoder,
                charEncoder,
                cconfig.getMessageConstraints(),
                incomingContentStrategy,
                outgoingContentStrategy,
                requestWriterFactory,
                responseParserFactory);
    }

}
