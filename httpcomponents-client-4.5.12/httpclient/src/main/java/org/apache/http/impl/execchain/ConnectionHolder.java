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

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.http.HttpClientConnection;
import org.apache.http.annotation.Contract;
import org.apache.http.annotation.ThreadingBehavior;
import org.apache.http.concurrent.Cancellable;
import org.apache.http.conn.ConnectionReleaseTrigger;
import org.apache.http.conn.HttpClientConnectionManager;

/**
 * Internal connection holder.
 *
 * @since 4.3
 */
@Contract(threading = ThreadingBehavior.SAFE)
class ConnectionHolder implements ConnectionReleaseTrigger, Cancellable, Closeable {

    private final Log log;

    private final HttpClientConnectionManager manager;
    // 被管理的连接
    private final HttpClientConnection managedConn;
    // 是否释放标志
    private final AtomicBoolean released;
    // 是否可以被 重用的标志
    private volatile boolean reusable;
    private volatile Object state;
    // 此连接的超时时间
    private volatile long validDuration;
    // 超时的单位
    private volatile TimeUnit timeUnit;

    public ConnectionHolder(
            final Log log,
            final HttpClientConnectionManager manager,
            final HttpClientConnection managedConn) {
        super();
        this.log = log;
        // 管理器
        this.manager = manager;
        // 连接
        this.managedConn = managedConn;
        // 是否释放
        this.released = new AtomicBoolean(false);
    }
    // 是否可重用
    public boolean isReusable() {
        return this.reusable;
    }
    // 标记可重用
    public void markReusable() {
        this.reusable = true;
    }
    // 标记不可重用
    public void markNonReusable() {
        this.reusable = false;
    }

    public void setState(final Object state) {
        this.state = state;
    }
    // 为连接设置  过期时间
    public void setValidFor(final long duration, final TimeUnit timeUnit) {
        synchronized (this.managedConn) {
            this.validDuration = duration;
            this.timeUnit = timeUnit;
        }
    }
    // 释放 连接
    private void releaseConnection(final boolean reusable) {
        if (this.released.compareAndSet(false, true)) {
            synchronized (this.managedConn) {
                // 如果可重用,则回收到 pool中
                if (reusable) {
                    // 回收连接
                    this.manager.releaseConnection(this.managedConn,
                            this.state, this.validDuration, this.timeUnit);
                } else {
                    try {
                        // 关闭连接
                        this.managedConn.close();
                        log.debug("Connection discarded");
                    } catch (final IOException ex) {
                        if (this.log.isDebugEnabled()) {
                            this.log.debug(ex.getMessage(), ex);
                        }
                    } finally {
                        this.manager.releaseConnection(
                                this.managedConn, null, 0, TimeUnit.MILLISECONDS);
                    }
                }
            }
        }
    }
    // 释放连接
    @Override
    public void releaseConnection() {
        // 回收连接
        releaseConnection(this.reusable);
    }
    // 中止连接
    // 当执行器请求发生 IOException时执行
    @Override
    public void abortConnection() {
        if (this.released.compareAndSet(false, true)) {
            synchronized (this.managedConn) {
                try {
                    // 连接关闭
                    this.managedConn.shutdown();
                    log.debug("Connection discarded");
                } catch (final IOException ex) {
                    if (this.log.isDebugEnabled()) {
                        this.log.debug(ex.getMessage(), ex);
                    }
                } finally {
                    // 回收资源
                    this.manager.releaseConnection(
                            this.managedConn, null, 0, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    @Override
    public boolean cancel() {
        final boolean alreadyReleased = this.released.get();
        log.debug("Cancelling request execution");
        abortConnection();
        return !alreadyReleased;
    }

    public boolean isReleased() {
        return this.released.get();
    }

    /*
     关闭连接
     可以看到参数为false,即不进行复用, 可以看到连接没有进行复用,即直接关闭掉
     */
    @Override
    public void close() throws IOException {
        releaseConnection(false);
    }

}
