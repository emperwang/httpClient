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
package org.apache.http.pool;

import org.apache.http.annotation.Contract;
import org.apache.http.annotation.ThreadingBehavior;
import org.apache.http.util.Args;

import java.util.concurrent.TimeUnit;

/**
 * Pool entry containing a pool connection object along with its route.
 * <p>
 * The connection contained by the pool entry may have an expiration time which
 * can be either set upon construction time or updated with
 * the {@link #updateExpiry(long, TimeUnit)}.
 * <p>
 * Pool entry may also have an object associated with it that represents
 * a connection state (usually a security principal or a unique token identifying
 * the user whose credentials have been used while establishing the connection).
 *
 * @param <T> the route type that represents the opposite endpoint of a pooled
 *   connection.
 * @param <C> the connection type.
 * @since 4.2
 */
@Contract(threading = ThreadingBehavior.SAFE_CONDITIONAL)
public abstract class PoolEntry<T, C> {

    private final String id;
    private final T route;
    private final C conn;
    private final long created;
    private final long validityDeadline;
    // 更新时间
    private long updated;

    private long expiry;

    private volatile Object state;

    /**
     * Creates new {@code PoolEntry} instance.
     *
     * @param id unique identifier of the pool entry. May be {@code null}.
     * @param route route to the opposite endpoint.
     * @param conn the connection.
     * @param timeToLive maximum time to live. May be zero if the connection
     *   does not have an expiry deadline.
     * @param timeUnit time unit.
     */
    public PoolEntry(final String id, final T route, final C conn,
            final long timeToLive, final TimeUnit timeUnit) {
        super();
        Args.notNull(route, "Route");
        Args.notNull(conn, "Connection");
        Args.notNull(timeUnit, "Time unit");
        this.id = id;
        // route
        this.route = route;
        // 连接
        this.conn = conn;
        // 创建时间
        this.created = System.currentTimeMillis();
        // 第一个的更新时间 就是 创建的时间
        this.updated = this.created;
        // 如果设置了过期时间,则过期时间为 this.created + timeUnit.toMillis(timeToLive)
        // 否则为:  this.validityDeadline = Long.MAX_VALUE
        // 此处的ttl 就是在连接池中 设置的 ttl
        if (timeToLive > 0) {
            // deadline时间 就是 创建时间加上 ttl 时间
            final long deadline = this.created + timeUnit.toMillis(timeToLive);
            // If the above overflows then default to Long.MAX_VALUE
            // 如果dealine 时间小于0, 那么就设置  validityDeadline时间为 Long.MAX_VALUE
            this.validityDeadline = deadline > 0 ? deadline : Long.MAX_VALUE;
        } else {
            // 默认的 有效deadline 就是 Long.MAX_VALUE
            this.validityDeadline = Long.MAX_VALUE;
        }
        // 过期时间 就是 validityDeadline
        this.expiry = this.validityDeadline;
    }

    /**
     * Creates new {@code PoolEntry} instance without an expiry deadline.
     *
     * @param id unique identifier of the pool entry. May be {@code null}.
     * @param route route to the opposite endpoint.
     * @param conn the connection.
     */
    public PoolEntry(final String id, final T route, final C conn) {
        this(id, route, conn, 0, TimeUnit.MILLISECONDS);
    }

    public String getId() {
        return this.id;
    }

    public T getRoute() {
        return this.route;
    }

    public C getConnection() {
        return this.conn;
    }

    public long getCreated() {
        return this.created;
    }

    /**
     * @since 4.4
     */
    public long getValidityDeadline() {
        return this.validityDeadline;
    }

    /**
     * @deprecated use {@link #getValidityDeadline()}
     */
    @Deprecated
    public long getValidUnit() {
        return this.validityDeadline;
    }

    public Object getState() {
        return this.state;
    }

    public void setState(final Object state) {
        this.state = state;
    }

    public synchronized long getUpdated() {
        return this.updated;
    }

    public synchronized long getExpiry() {
        return this.expiry;
    }
    // 更新过期时间
    public synchronized void updateExpiry(final long time, final TimeUnit timeUnit) {
        Args.notNull(timeUnit, "Time unit");
        // update 为当前系统时间
        this.updated = System.currentTimeMillis();
        final long newExpiry;
        if (time > 0) {
            // 更新 过期时间
            newExpiry = this.updated + timeUnit.toMillis(time);
        } else {
            // 否则过期时间为 Long.MAX_VALUE
            newExpiry = Long.MAX_VALUE;
        }
        // 选择新的 newExpiry 和  validityDeadline 中的较小值
        this.expiry = Math.min(newExpiry, this.validityDeadline);
    }
    // 判断连接是否已经过期
    public synchronized boolean isExpired(final long now) {
        // 当前时间 大于 过期时间, 则表示已经 过期
        return now >= this.expiry;
    }

    /**
     * Invalidates the pool entry and closes the pooled connection associated
     * with it.
     */
    public abstract void close();

    /**
     * Returns {@code true} if the pool entry has been invalidated.
     */
    public abstract boolean isClosed();

    @Override
    public String toString() {
        final StringBuilder buffer = new StringBuilder();
        buffer.append("[id:");
        buffer.append(this.id);
        buffer.append("][route:");
        buffer.append(this.route);
        buffer.append("][state:");
        buffer.append(this.state);
        buffer.append("]");
        return buffer.toString();
    }

}
