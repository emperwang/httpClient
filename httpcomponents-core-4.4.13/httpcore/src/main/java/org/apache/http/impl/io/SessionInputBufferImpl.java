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
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;

import org.apache.http.MessageConstraintException;
import org.apache.http.config.MessageConstraints;
import org.apache.http.io.BufferInfo;
import org.apache.http.io.HttpTransportMetrics;
import org.apache.http.io.SessionInputBuffer;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.Args;
import org.apache.http.util.Asserts;
import org.apache.http.util.ByteArrayBuffer;
import org.apache.http.util.CharArrayBuffer;

/**
 * Abstract base class for session input buffers that stream data from
 * an arbitrary {@link InputStream}. This class buffers input data in
 * an internal byte array for optimal input performance.
 * <p>
 * {@link #readLine(CharArrayBuffer)} and {@link #readLine()} methods of this
 * class treat a lone LF as valid line delimiters in addition to CR-LF required
 * by the HTTP specification.
 *
 * @since 4.3
 */
public class SessionInputBufferImpl implements SessionInputBuffer, BufferInfo {

    private final HttpTransportMetricsImpl metrics;
    private final byte[] buffer;
    private final ByteArrayBuffer lineBuffer;
    private final int minChunkLimit;
    private final MessageConstraints constraints;
    private final CharsetDecoder decoder;
    // 输入流
    private InputStream inStream;
    private int bufferPos;
    private int bufferLen;
    private CharBuffer cbuf;

    /**
     * Creates new instance of SessionInputBufferImpl.
     *
     * @param metrics HTTP transport metrics.
     * @param bufferSize buffer size. Must be a positive number.
     * @param minChunkLimit size limit below which data chunks should be buffered in memory
     *   in order to minimize native method invocations on the underlying network socket.
     *   The optimal value of this parameter can be platform specific and defines a trade-off
     *   between performance of memory copy operations and that of native method invocation.
     *   If negative default chunk limited will be used.
     * @param constraints Message constraints. If {@code null}
     *   {@link MessageConstraints#DEFAULT} will be used.
     * @param charDecoder CharDecoder to be used for decoding HTTP protocol elements.
     *   If {@code null} simple type cast will be used for byte to char conversion.
     */
    public SessionInputBufferImpl(
            final HttpTransportMetricsImpl metrics,
            final int bufferSize,
            final int minChunkLimit,
            final MessageConstraints constraints,
            final CharsetDecoder charDecoder) {
        Args.notNull(metrics, "HTTP transport metrcis");
        Args.positive(bufferSize, "Buffer size");
        this.metrics = metrics;
        // 缓存数据
        this.buffer = new byte[bufferSize];
        this.bufferPos = 0;
        this.bufferLen = 0;
        this.minChunkLimit = minChunkLimit >= 0 ? minChunkLimit : 512;
        this.constraints = constraints != null ? constraints : MessageConstraints.DEFAULT;
        this.lineBuffer = new ByteArrayBuffer(bufferSize);
        this.decoder = charDecoder;
    }

    public SessionInputBufferImpl(
            final HttpTransportMetricsImpl metrics,
            final int bufferSize) {
        this(metrics, bufferSize, bufferSize, null, null);
    }

    public void bind(final InputStream inputStream) {
        this.inStream = inputStream;
    }

    public boolean isBound() {
        return this.inStream != null;
    }

    @Override
    public int capacity() {
        return this.buffer.length;
    }

    @Override
    public int length() {
        return this.bufferLen - this.bufferPos;
    }

    @Override
    public int available() {
        return capacity() - length();
    }
    // 从输入流中读取数据
    private int streamRead(final byte[] b, final int off, final int len) throws IOException {
        Asserts.notNull(this.inStream, "Input stream");
        return this.inStream.read(b, off, len);
    }
    // 填充buffer,即从 输入流中读取数据
    public int fillBuffer() throws IOException {
        // compact the buffer if necessary
        // 如果后缓存数据,则compact buffer
        if (this.bufferPos > 0) {
            final int len = this.bufferLen - this.bufferPos;
            if (len > 0) {
                System.arraycopy(this.buffer, this.bufferPos, this.buffer, 0, len);
            }
            this.bufferPos = 0;
            this.bufferLen = len;
        }
        final int readLen;
        final int off = this.bufferLen;
        final int len = this.buffer.length - off;
        // 从输入流中读取数据到 buffer中
        readLen = streamRead(this.buffer, off, len);
        if (readLen == -1) {
            return -1;
        }
        this.bufferLen = off + readLen;
        this.metrics.incrementBytesTransferred(readLen);
        // 返回读取的数据
        return readLen;
    }

    public boolean hasBufferedData() {
        return this.bufferPos < this.bufferLen;
    }

    public void clear() {
        this.bufferPos = 0;
        this.bufferLen = 0;
    }

    @Override
    public int read() throws IOException {
        int noRead;
        while (!hasBufferedData()) {
            noRead = fillBuffer();
            if (noRead == -1) {
                return -1;
            }
        }
        return this.buffer[this.bufferPos++] & 0xff;
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        if (b == null) {
            return 0;
        }
        if (hasBufferedData()) {
            final int chunk = Math.min(len, this.bufferLen - this.bufferPos);
            System.arraycopy(this.buffer, this.bufferPos, b, off, chunk);
            this.bufferPos += chunk;
            return chunk;
        }
        // If the remaining capacity is big enough, read directly from the
        // underlying input stream bypassing the buffer.
        if (len > this.minChunkLimit) {
            final int readLen = streamRead(b, off, len);
            if (readLen > 0) {
                this.metrics.incrementBytesTransferred(readLen);
            }
            return readLen;
        }
        // otherwise read to the buffer first
        while (!hasBufferedData()) {
            final int noRead = fillBuffer();
            if (noRead == -1) {
                return -1;
            }
        }
        final int chunk = Math.min(len, this.bufferLen - this.bufferPos);
        System.arraycopy(this.buffer, this.bufferPos, b, off, chunk);
        this.bufferPos += chunk;
        return chunk;
    }

    @Override
    public int read(final byte[] b) throws IOException {
        if (b == null) {
            return 0;
        }
        return read(b, 0, b.length);
    }

    /**
     * Reads a complete line of characters up to a line delimiter from this
     * session buffer into the given line buffer. The number of chars actually
     * read is returned as an integer. The line delimiter itself is discarded.
     * If no char is available because the end of the stream has been reached,
     * the value {@code -1} is returned. This method blocks until input
     * data is available, end of file is detected, or an exception is thrown.
     * <p>
     * This method treats a lone LF as a valid line delimiters in addition
     * to CR-LF required by the HTTP specification.
     *
     * @param      charbuffer   the line buffer.
     * @return     one line of characters
     * @throws  IOException  if an I/O error occurs.
     */
    // 从输入流中读取数据
    // charbuffer 存储读取到的数据
    @Override
    public int readLine(final CharArrayBuffer charbuffer) throws IOException {
        Args.notNull(charbuffer, "Char array buffer");
        // 获取最大的行长度, 默认的是-1
        final int maxLineLen = this.constraints.getMaxLineLength();
        int noRead = 0;
        boolean retry = true;
        // 此处的 while(retry) 即 读取一行的数据,即读取到 HTTP.LF 表示读取到一行
        // 死循环,持续从  输入流中进行数据的读取
        while (retry) {
            // attempt to find end of line (LF)
            int pos = -1;
            // 查看是否读取到了一行
            for (int i = this.bufferPos; i < this.bufferLen; i++) {
                if (this.buffer[i] == HTTP.LF) {
                    // 此pos记录 读取到的数据中  LF的位置,即pos到bufferLen 之间的就是一行的数据
                    pos = i;
                    break;
                }
            }
            // 查看当前读取到的长度  是否大于了一行最大长度
            if (maxLineLen > 0) {
                final int currentLen = this.lineBuffer.length()
                        + (pos >= 0 ? pos : this.bufferLen) - this.bufferPos;
                if (currentLen >= maxLineLen) {
                    throw new MessageConstraintException("Maximum line length limit exceeded");
                }
            }
            // this.buffer 是缓存从inputStream中读取的数据
            // this.lineBuffer 数缓存一行的数据,数据来源于 this.buffer
            if (pos != -1) {
                // end of line found.
                // pos不为-1,但是lineBuffer为空 说明读取到了一行
                if (this.lineBuffer.isEmpty()) {
                    // the entire line is preset in the read buffer
                    // 把读取到的 一行数据 追加到  read buffer中
                    // pos不等于-1,表示已经读取到了 HTTP.LF,即读取到了一行数据到 buffer中
                    // lineFromReadBuffer 就是直接把buffer中pos到 bufferLen之间的一行数据进行读取并处理
                    return lineFromReadBuffer(charbuffer, pos);
                }
                retry = false;
                final int len = pos + 1 - this.bufferPos;
                this.lineBuffer.append(this.buffer, this.bufferPos, len);
                this.bufferPos = pos + 1;
            } else {
                // end of line not found
                // 是否读取到了数据
                if (hasBufferedData()) {
                    // buffer中的有效数据
                    final int len = this.bufferLen - this.bufferPos;
                    // 把buffer中的数据 放入到 lineBuffer中
                    this.lineBuffer.append(this.buffer, this.bufferPos, len);
                    this.bufferPos = this.bufferLen;
                }
                // 从输入流中读取数据
                noRead = fillBuffer();
                // 如果没有读到数据,则设置 retry为false; 即退出循环,不再进行数据的读取
                if (noRead == -1) {
                    retry = false;
                }
            }
        }
        if (noRead == -1 && this.lineBuffer.isEmpty()) {
            // indicate the end of stream
            return -1;
        }
        return lineFromLineBuffer(charbuffer);
    }

    /**
     * Reads a complete line of characters up to a line delimiter from this
     * session buffer. The line delimiter itself is discarded. If no char is
     * available because the end of the stream has been reached,
     * {@code null} is returned. This method blocks until input data is
     * available, end of file is detected, or an exception is thrown.
     * <p>
     * This method treats a lone LF as a valid line delimiters in addition
     * to CR-LF required by the HTTP specification.
     *
     * @return HTTP line as a string
     * @throws  IOException  if an I/O error occurs.
     */
    private int lineFromLineBuffer(final CharArrayBuffer charbuffer)
            throws IOException {
        // discard LF if found
        // lineBuffer的长度
        int len = this.lineBuffer.length();
        if (len > 0) {
            if (this.lineBuffer.byteAt(len - 1) == HTTP.LF) {
                len--;
            }
            // discard CR if found
            if (len > 0) {
                if (this.lineBuffer.byteAt(len - 1) == HTTP.CR) {
                    len--;
                }
            }
        }
        if (this.decoder == null) {
            charbuffer.append(this.lineBuffer, 0, len);
        } else {
            final ByteBuffer bbuf =  ByteBuffer.wrap(this.lineBuffer.buffer(), 0, len);
            len = appendDecoded(charbuffer, bbuf);
        }
        this.lineBuffer.clear();
        return len;
    }
    // 从输入缓存区中读取 一行数据
    // bufferPos 到  bufferLen 之间的数据 就是一行的数据
    private int lineFromReadBuffer(final CharArrayBuffer charbuffer, final int position)
            throws IOException {
        int pos = position;
        // buffer的当前位置
        final int off = this.bufferPos;
        int len;
        this.bufferPos = pos + 1;
        // 去除 CR 符号; 即\n
        // 当读取到 \r 时就认为是一行数据了
        if (pos > off && this.buffer[pos - 1] == HTTP.CR) {
            // skip CR if found
            pos--;
        }
        len = pos - off;
        // 如果没有解码器,则直接追加数据
        if (this.decoder == null) {
            charbuffer.append(this.buffer, off, len);
        } else {
            // 如果有解码器,则解码后,再追加
            final ByteBuffer bbuf =  ByteBuffer.wrap(this.buffer, off, len);
            len = appendDecoded(charbuffer, bbuf);
        }
        // 返回一样的长度
        return len;
    }
    // 把读取到的数据 解码,并追加到 charbuffer中
    private int appendDecoded(
            final CharArrayBuffer charbuffer, final ByteBuffer bbuf) throws IOException {
        if (!bbuf.hasRemaining()) {
            return 0;
        }
        if (this.cbuf == null) {
            this.cbuf = CharBuffer.allocate(1024);
        }
        this.decoder.reset();
        int len = 0;
        while (bbuf.hasRemaining()) {
            final CoderResult result = this.decoder.decode(bbuf, this.cbuf, true);
            len += handleDecodingResult(result, charbuffer, bbuf);
        }
        final CoderResult result = this.decoder.flush(this.cbuf);
        len += handleDecodingResult(result, charbuffer, bbuf);
        this.cbuf.clear();
        return len;
    }

    private int handleDecodingResult(
            final CoderResult result,
            final CharArrayBuffer charbuffer,
            final ByteBuffer bbuf) throws IOException {
        if (result.isError()) {
            result.throwException();
        }
        this.cbuf.flip();
        final int len = this.cbuf.remaining();
        while (this.cbuf.hasRemaining()) {
            charbuffer.append(this.cbuf.get());
        }
        this.cbuf.compact();
        return len;
    }

    @Override
    public String readLine() throws IOException {
        final CharArrayBuffer charbuffer = new CharArrayBuffer(64);
        final int readLen = readLine(charbuffer);
        return readLen != -1 ? charbuffer.toString() : null;
    }

    @Override
    public boolean isDataAvailable(final int timeout) throws IOException {
        return hasBufferedData();
    }

    @Override
    public HttpTransportMetrics getMetrics() {
        return this.metrics;
    }

}
