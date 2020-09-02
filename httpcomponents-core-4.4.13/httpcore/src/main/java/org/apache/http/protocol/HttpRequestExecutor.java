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

package org.apache.http.protocol;

import java.io.IOException;

import org.apache.http.HttpClientConnection;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.ProtocolException;
import org.apache.http.ProtocolVersion;
import org.apache.http.annotation.Contract;
import org.apache.http.annotation.ThreadingBehavior;
import org.apache.http.util.Args;

/**
 * {@code HttpRequestExecutor} is a client side HTTP protocol handler based
 * on the blocking (classic) I/O model.
 * <p>
 * {@code HttpRequestExecutor} relies on {@link HttpProcessor} to generate
 * mandatory protocol headers for all outgoing messages and apply common,
 * cross-cutting message transformations to all incoming and outgoing messages.
 * Application specific processing can be implemented outside
 * {@code HttpRequestExecutor} once the request has been executed and
 * a response has been received.
 *
 * @since 4.0
 */
@Contract(threading = ThreadingBehavior.IMMUTABLE)
public class HttpRequestExecutor {

    public static final int DEFAULT_WAIT_FOR_CONTINUE = 3000;

    private final int waitForContinue;

    /**
     * Creates new instance of HttpRequestExecutor.
     *
     * @since 4.3
     */
    public HttpRequestExecutor(final int waitForContinue) {
        super();
        this.waitForContinue = Args.positive(waitForContinue, "Wait for continue time");
    }

    public HttpRequestExecutor() {
        this(DEFAULT_WAIT_FOR_CONTINUE);
    }

    /**
     * Decide whether a response comes with an entity.
     * The implementation in this class is based on RFC 2616.
     * <p>
     * Derived executors can override this method to handle
     * methods and response codes not specified in RFC 2616.
     * </p>
     *
     * @param request   the request, to obtain the executed method
     * @param response  the response, to obtain the status code
     */
    protected boolean canResponseHaveBody(final HttpRequest request,
                                          final HttpResponse response) {

        if ("HEAD".equalsIgnoreCase(request.getRequestLine().getMethod())) {
            return false;
        }
        final int status = response.getStatusLine().getStatusCode();
        return status >= HttpStatus.SC_OK
            && status != HttpStatus.SC_NO_CONTENT
            && status != HttpStatus.SC_NOT_MODIFIED
            && status != HttpStatus.SC_RESET_CONTENT;
    }

    /**
     * Sends the request and obtain a response.
     *
     * @param request   the request to execute.
     * @param conn      the connection over which to execute the request.
     *
     * @return  the response to the request.
     *
     * @throws IOException in case of an I/O error.
     * @throws HttpException in case of HTTP protocol violation or a processing
     *   problem.
     */
    // 执行请求,并获取到 response
    public HttpResponse execute(
            final HttpRequest request,
            final HttpClientConnection conn,
            final HttpContext context) throws IOException, HttpException {
        Args.notNull(request, "HTTP request");
        Args.notNull(conn, "Client connection");
        Args.notNull(context, "HTTP context");
        try {
            // 1. 发送请求,并得到 响应结果
            HttpResponse response = doSendRequest(request, conn, context);
            if (response == null) {
                // 2. 如果上面没有得到响应体,这里会在此执行  直到得到响应体
                response = doReceiveResponse(request, conn, context);
            }
            return response;
        } catch (final IOException ex) {
            closeConnection(conn);
            throw ex;
        } catch (final HttpException ex) {
            closeConnection(conn);
            throw ex;
        } catch (final RuntimeException ex) {
            closeConnection(conn);
            throw ex;
        }
    }

    private static void closeConnection(final HttpClientConnection conn) {
        try {
            conn.close();
        } catch (final IOException ignore) {
        }
    }

    /**
     * Pre-process the given request using the given protocol processor and
     * initiates the process of request execution.
     *
     * @param request   the request to prepare
     * @param processor the processor to use
     * @param context   the context for sending the request
     *
     * @throws IOException in case of an I/O error.
     * @throws HttpException in case of HTTP protocol violation or a processing
     *   problem.
     */
    public void preProcess(
            final HttpRequest request,
            final HttpProcessor processor,
            final HttpContext context) throws HttpException, IOException {
        Args.notNull(request, "HTTP request");
        Args.notNull(processor, "HTTP processor");
        Args.notNull(context, "HTTP context");
        context.setAttribute(HttpCoreContext.HTTP_REQUEST, request);
        processor.process(request, context);
    }

    /**
     * Send the given request over the given connection.
     * <p>
     * This method also handles the expect-continue handshake if necessary.
     * If it does not have to handle an expect-continue handshake, it will
     * not use the connection for reading or anything else that depends on
     * data coming in over the connection.
     *
     * @param request   the request to send, already
     *                  {@link #preProcess preprocessed}
     * @param conn      the connection over which to send the request,
     *                  already established
     * @param context   the context for sending the request
     *
     * @return  a terminal response received as part of an expect-continue
     *          handshake, or
     *          {@code null} if the expect-continue handshake is not used
     *
     * @throws IOException in case of an I/O error.
     * @throws HttpException in case of HTTP protocol violation or a processing
     *   problem.
     */
    // 真正发送请求
    protected HttpResponse doSendRequest(
            final HttpRequest request,
            final HttpClientConnection conn,
            final HttpContext context) throws IOException, HttpException {
        Args.notNull(request, "HTTP request");
        Args.notNull(conn, "Client connection");
        Args.notNull(context, "HTTP context");
        // 记录响应
        HttpResponse response = null;
        // 记录一些属性到 context中
        context.setAttribute(HttpCoreContext.HTTP_CONNECTION, conn);
        context.setAttribute(HttpCoreContext.HTTP_REQ_SENT, Boolean.FALSE);
        // 1. 写请求头数据
        // "GET /index.html HTTP/1.1"  以及  requestHeader 写入到buffer中
        // ---- 重点 --------
        conn.sendRequestHeader(request);
        // 2. 如果有请求体,则这里处理请求体内容
        if (request instanceof HttpEntityEnclosingRequest) {
            // Check for expect-continue handshake. We have to flush the
            // headers and wait for an 100-continue response to handle it.
            // If we get a different response, we must not send the entity.
            boolean sendentity = true;
            // 2.1 获取此次请求的协议版本
            final ProtocolVersion ver =
                request.getRequestLine().getProtocolVersion();
            // 2.2 根据请求头Expect ,判断是否继续进行
            if (((HttpEntityEnclosingRequest) request).expectContinue() &&
                !ver.lessEquals(HttpVersion.HTTP_1_0)) {
                // 2.3 flush 缓存到对端
                conn.flush();
                // As suggested by RFC 2616 section 8.2.3, we don't wait for a
                // 100-continue response forever. On timeout, send the entity.
                // 2.3 这里判断 是否有响应, 主要是从输入流中读取数据 或者 看输入缓存中是否有数据
                if (conn.isResponseAvailable(this.waitForContinue)) {
                    // 2.4 解析响应信息
                    response = conn.receiveResponseHeader();
                    // 是否有 请求体
                    // 2.5 head 请求无请求体, 然后根据响应码 来判断是否有请求体
                    if (canResponseHaveBody(request, response)) {
                        // 2.6 解析请求体
                        conn.receiveResponseEntity(response);
                    }
                    // 2.6 根据响应码 来判断是否继续
                    final int status = response.getStatusLine().getStatusCode();
                    if (status < 200) {
                        if (status != HttpStatus.SC_CONTINUE) {
                            throw new ProtocolException(
                                    "Unexpected response: " + response.getStatusLine());
                        }
                        // discard 100-continue
                        response = null;
                    } else {
                        sendentity = false;
                    }
                }
            }
            // 3. 发送请求体
            if (sendentity) {
                conn.sendRequestEntity((HttpEntityEnclosingRequest) request);
            }
        }
        // 3. flush 缓存,即把数据写出
        conn.flush();
        // 4. 记录此处请求完成
        context.setAttribute(HttpCoreContext.HTTP_REQ_SENT, Boolean.TRUE);
        // 5. 返回响应
        return response;
    }

    /**
     * Waits for and receives a response.
     * This method will automatically ignore intermediate responses
     * with status code 1xx.
     *
     * @param request   the request for which to obtain the response
     * @param conn      the connection over which the request was sent
     * @param context   the context for receiving the response
     *
     * @return  the terminal response, not yet post-processed
     *
     * @throws IOException in case of an I/O error.
     * @throws HttpException in case of HTTP protocol violation or a processing
     *   problem.
     */
    // 响应的一个解析
    protected HttpResponse doReceiveResponse(
            final HttpRequest request,
            final HttpClientConnection conn,
            final HttpContext context) throws HttpException, IOException {
        Args.notNull(request, "HTTP request");
        Args.notNull(conn, "Client connection");
        Args.notNull(context, "HTTP context");
        HttpResponse response = null;
        int statusCode = 0;
        // 1. 如果还没有response 或者  statusCode 小于200, 则持续读取响应
        while (response == null || statusCode < HttpStatus.SC_OK) {
            // 1.1 接收请求头
            response = conn.receiveResponseHeader();
            // 1.2 获取响应中的响应码
            statusCode = response.getStatusLine().getStatusCode();
            if (statusCode < HttpStatus.SC_CONTINUE) {
                throw new ProtocolException("Invalid response: " + response.getStatusLine());
            }
            // 1.3 查看响应中是否有响应体
            if (canResponseHaveBody(request, response)) {
                // 1.4如果有响应体,则解析响应体内容
                conn.receiveResponseEntity(response);
            }

        } // while intermediate response
        // 2. 返回接收到的响应
        return response;
    }

    /**
     * Post-processes the given response using the given protocol processor and
     * completes the process of request execution.
     * <p>
     * This method does <i>not</i> read the response entity, if any.
     * The connection over which content of the response entity is being
     * streamed from cannot be reused until
     * {@link org.apache.http.util.EntityUtils#consume(org.apache.http.HttpEntity)}
     * has been invoked.
     *
     * @param response  the response object to post-process
     * @param processor the processor to use
     * @param context   the context for post-processing the response
     *
     * @throws IOException in case of an I/O error.
     * @throws HttpException in case of HTTP protocol violation or a processing
     *   problem.
     */
    public void postProcess(
            final HttpResponse response,
            final HttpProcessor processor,
            final HttpContext context) throws HttpException, IOException {
        Args.notNull(response, "HTTP response");
        Args.notNull(processor, "HTTP processor");
        Args.notNull(context, "HTTP context");
        context.setAttribute(HttpCoreContext.HTTP_RESPONSE, response);
        processor.process(response, context);
    }

} // class HttpRequestExecutor
