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

package org.apache.http.impl.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;

import org.apache.http.io.BufferInfo;
import org.apache.http.io.HttpTransportMetrics;
import org.apache.http.io.SessionOutputBuffer;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.Args;
import org.apache.http.util.Asserts;
import org.apache.http.util.ByteArrayBuffer;
import org.apache.http.util.CharArrayBuffer;

/**
 * Abstract base class for session output buffers that stream data to
 * an arbitrary {@link OutputStream}. This class buffers small chunks of
 * output data in an internal byte array for optimal output performance.
 * <p>
 * {@link #writeLine(CharArrayBuffer)} and {@link #writeLine(String)} methods
 * of this class use CR-LF as a line delimiter.
 *
 * @since 4.3
 */
public class SessionOutputBufferImpl implements SessionOutputBuffer, BufferInfo {

    private static final byte[] CRLF = new byte[] {HTTP.CR, HTTP.LF};
    // metrics
    private final HttpTransportMetricsImpl metrics;
    // 缓冲区
    private final ByteArrayBuffer buffer;
    // 片段大小
    private final int fragementSizeHint;
    // 编码
    private final CharsetEncoder encoder;
    // socket 输出流
    private OutputStream outStream;
    private ByteBuffer bbuf;

    /**
     * Creates new instance of SessionOutputBufferImpl.
     *
     * @param metrics HTTP transport metrics.
     * @param bufferSize buffer size. Must be a positive number.
     * @param fragementSizeHint fragment size hint defining a minimal size of a fragment
     *   that should be written out directly to the socket bypassing the session buffer.
     *   Value {@code 0} disables fragment buffering.
     * @param charEncoder charEncoder to be used for encoding HTTP protocol elements.
     *   If {@code null} simple type cast will be used for char to byte conversion.
     */
    public SessionOutputBufferImpl(
            final HttpTransportMetricsImpl metrics,
            final int bufferSize,
            final int fragementSizeHint,
            final CharsetEncoder charEncoder) {
        super();
        Args.positive(bufferSize, "Buffer size");
        Args.notNull(metrics, "HTTP transport metrcis");
        this.metrics = metrics;
        this.buffer = new ByteArrayBuffer(bufferSize);
        this.fragementSizeHint = fragementSizeHint >= 0 ? fragementSizeHint : 0;
        this.encoder = charEncoder;
    }

    public SessionOutputBufferImpl(
            final HttpTransportMetricsImpl metrics,
            final int bufferSize) {
        this(metrics, bufferSize, bufferSize, null);
    }

    public void bind(final OutputStream outStream) {
        this.outStream = outStream;
    }

    public boolean isBound() {
        return this.outStream != null;
    }

    @Override
    public int capacity() {
        return this.buffer.capacity();
    }

    @Override
    public int length() {
        return this.buffer.length();
    }

    @Override
    public int available() {
        return capacity() - length();
    }
    // 把buffer中的消息写出去
    private void streamWrite(final byte[] b, final int off, final int len) throws IOException {
        Asserts.notNull(outStream, "Output stream");
        this.outStream.write(b, off, len);
    }
    // stream flush操作
    private void flushStream() throws IOException {
        if (this.outStream != null) {
            this.outStream.flush();
        }
    }
    // flushBuffer 即把buffer中的信息 写出
    private void flushBuffer() throws IOException {
        final int len = this.buffer.length();
        if (len > 0) {
            streamWrite(this.buffer.buffer(), 0, len);
            this.buffer.clear();
            this.metrics.incrementBytesTransferred(len);
        }
    }
    // flush 操作
    @Override
    public void flush() throws IOException {
        // 把buffer中的消息写出
        flushBuffer();
        // 对stream进行flush
        flushStream();
    }

    @Override
    public void write(final byte[] b, final int off, final int len) throws IOException {
        if (b == null) {
            return;
        }
        // Do not want to buffer large-ish chunks
        // if the byte array is larger then MIN_CHUNK_LIMIT
        // write it directly to the output stream
        if (len > this.fragementSizeHint || len > this.buffer.capacity()) {
            // flush the buffer
            flushBuffer();
            // write directly to the out stream
            streamWrite(b, off, len);
            this.metrics.incrementBytesTransferred(len);
        } else {
            // Do not let the buffer grow unnecessarily
            final int freecapacity = this.buffer.capacity() - this.buffer.length();
            if (len > freecapacity) {
                // flush the buffer
                flushBuffer();
            }
            // buffer
            this.buffer.append(b, off, len);
        }
    }
    // 写出数据
    @Override
    public void write(final byte[] b) throws IOException {
        if (b == null) {
            return;
        }
        write(b, 0, b.length);
    }
    // 把字节数据写入到 buffer中
    @Override
    public void write(final int b) throws IOException {
        // 如果允许分段,则直接追加到buffer中
        if (this.fragementSizeHint > 0) {
            if (this.buffer.isFull()) {
                flushBuffer();
            }
            // 追加到   buffer中
            this.buffer.append(b);
        } else {
            // 如果不允许片段, 那就直接从流中写出
            flushBuffer();
            this.outStream.write(b);
        }
    }

    /**
     * Writes characters from the specified string followed by a line delimiter
     * to this session buffer.
     * <p>
     * This method uses CR-LF as a line delimiter.
     *
     * @param      s   the line.
     * @throws  IOException  if an I/O error occurs.
     */
    // 按行写出到 stream中
    @Override
    public void writeLine(final String s) throws IOException {
        if (s == null) {
            return;
        }
        if (s.length() > 0) {
            if (this.encoder == null) {
                for (int i = 0; i < s.length(); i++) {
                    write(s.charAt(i));
                }
            } else {
                final CharBuffer cbuf = CharBuffer.wrap(s);
                writeEncoded(cbuf);
            }
        }
        write(CRLF);
    }

    /**
     * Writes characters from the specified char array followed by a line
     * delimiter to this session buffer.
     * <p>
     * This method uses CR-LF as a line delimiter.
     *
     * @param      charbuffer the buffer containing chars of the line.
     * @throws  IOException  if an I/O error occurs.
     */
    // 此在写http 的请求头时调用.即把请求头信息写入到buffer中
    @Override
    public void writeLine(final CharArrayBuffer charbuffer) throws IOException {
        if (charbuffer == null) {
            return;
        }
        // 如果没有 编码
        // 则直接写入
        if (this.encoder == null) {
            int off = 0;
            int remaining = charbuffer.length();
            // 把输出写入到 buffer中
            while (remaining > 0) {
                // chunk 可写入的大小
                int chunk = this.buffer.capacity() - this.buffer.length();
                // 选择一个小的
                chunk = Math.min(chunk, remaining);
                // 开始追加数据到  buffer中
                if (chunk > 0) {
                    this.buffer.append(charbuffer, off, chunk);
                }
                // 如果buffer中的数据满了,则flush一次,即把buffer中的数据写入到 socket中
                if (this.buffer.isFull()) {
                    flushBuffer();
                }
                // 位置信息 更新
                off += chunk;
                remaining -= chunk;
            }
        } else {
            // 如果有编码,则使用编码写入
            // charBuffer 使用
            final CharBuffer cbuf = CharBuffer.wrap(charbuffer.buffer(), 0, charbuffer.length());
            // 编码数据 然后再写入到 buffer中
            writeEncoded(cbuf);
        }
        // 写入换行符
        write(CRLF);
    }
    // 先编码 在写入到到缓存
    private void writeEncoded(final CharBuffer cbuf) throws IOException {
        if (!cbuf.hasRemaining()) {
            return;
        }
        // 中间缓存区,用于存储编码的信息
        if (this.bbuf == null) {
            this.bbuf = ByteBuffer.allocate(1024);
        }
        // 编码重置
        this.encoder.reset();
        while (cbuf.hasRemaining()) {
            // 对要写入的信息 进行 编码
            final CoderResult result = this.encoder.encode(cbuf, this.bbuf, true);
            // 处理编码后的信息
            handleEncodingResult(result);
        }
        final CoderResult result = this.encoder.flush(this.bbuf);
        handleEncodingResult(result);
        this.bbuf.clear();
    }
    // 把编码后的信息  写入到buffer中
    private void handleEncodingResult(final CoderResult result) throws IOException {
        // 如果有错误,则抛出错误
        if (result.isError()) {
            result.throwException();
        }
        // 准备读 bbuf中的信息
        this.bbuf.flip();
        while (this.bbuf.hasRemaining()) {
            // 把bbuf中的信息 写到 buffer中
            write(this.bbuf.get());
        }
        // 把buffer中省下数据拷贝到开始
        this.bbuf.compact();
    }

    @Override
    public HttpTransportMetrics getMetrics() {
        return this.metrics;
    }

}
