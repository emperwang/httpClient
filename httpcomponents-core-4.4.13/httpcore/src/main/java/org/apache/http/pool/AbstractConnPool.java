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

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.http.annotation.Contract;
import org.apache.http.annotation.ThreadingBehavior;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.util.Args;
import org.apache.http.util.Asserts;

/**
 * Abstract synchronous (blocking) pool of connections.
 * <p>
 * Please note that this class does not maintain its own pool of execution {@link Thread}s.
 * Therefore, one <b>must</b> call {@link Future#get()} or {@link Future#get(long, TimeUnit)}
 * method on the {@link Future} object returned by the
 * {@link #lease(Object, Object, FutureCallback)} method in order for the lease operation
 * to complete.
 *
 * @param <T> the route type that represents the opposite endpoint of a pooled
 *   connection.
 * @param <C> the connection type.
 * @param <E> the type of the pool entry containing a pooled connection.
 * @since 4.2
 */
@Contract(threading = ThreadingBehavior.SAFE_CONDITIONAL)
public abstract class AbstractConnPool<T, C, E extends PoolEntry<T, C>>
                                               implements ConnPool<T, E>, ConnPoolControl<T> {

    private final Lock lock;
    private final Condition condition;
    // 连接工厂类
    private final ConnFactory<T, C> connFactory;
    // 记录host 到 routeSpecificPool的映射关闭
    private final Map<T, RouteSpecificPool<T, C, E>> routeToPool;
    // 租用的连接
    private final Set<E> leased;
    // 可用的连接
    private final LinkedList<E> available;
    // 等待的连接
    private final LinkedList<Future<E>> pending;
    private final Map<T, Integer> maxPerRoute;
    // 是否关闭
    private volatile boolean isShutDown;
    // 默认的最大 route
    private volatile int defaultMaxPerRoute;
    // 最大总的连接数
    private volatile int maxTotal;
    private volatile int validateAfterInactivity;

    public AbstractConnPool(
            final ConnFactory<T, C> connFactory,
            final int defaultMaxPerRoute,
            final int maxTotal) {
        super();
        // 工厂类 -- InternalConnectionFactory
        this.connFactory = Args.notNull(connFactory, "Connection factory");
        // 每一个route的最大值
        this.defaultMaxPerRoute = Args.positive(defaultMaxPerRoute, "Max per route value");
        // 连接总数
        this.maxTotal = Args.positive(maxTotal, "Max total value");
        // 锁
        this.lock = new ReentrantLock();
        this.condition = this.lock.newCondition();
        // route 对应的pool
        this.routeToPool = new HashMap<T, RouteSpecificPool<T, C, E>>();
        // 租用的连接
        this.leased = new HashSet<E>();
        // 可用的
        this.available = new LinkedList<E>();
        // 等待 连接的  future
        this.pending = new LinkedList<Future<E>>();
        // 每个route的最大值
        this.maxPerRoute = new HashMap<T, Integer>();
    }

    /**
     * Creates a new entry for the given connection with the given route.
     */
    protected abstract E createEntry(T route, C conn);

    /**
     * @since 4.3
     */
    protected void onLease(final E entry) {
    }

    /**
     * @since 4.3
     */
    protected void onRelease(final E entry) {
    }

    /**
     * @since 4.4
     */
    protected void onReuse(final E entry) {
    }

    /**
     * @since 4.4
     */
    protected boolean validate(final E entry) {
        return true;
    }

    public boolean isShutdown() {
        return this.isShutDown;
    }

    /**
     * Shuts down the pool.
     */
    // 关闭连接池
    public void shutdown() throws IOException {
        if (this.isShutDown) {
            return ;
        }
        this.isShutDown = true;
        this.lock.lock();
        try {
            // 遍历所有可用的  连接,然后进行关闭
            for (final E entry: this.available) {
                entry.close();
            }
            // 遍历租用的  进行关闭
            for (final E entry: this.leased) {
                entry.close();
            }
            // routePool 进行关闭
            for (final RouteSpecificPool<T, C, E> pool: this.routeToPool.values()) {
                pool.shutdown();
            }
            this.routeToPool.clear();
            this.leased.clear();
            this.available.clear();
        } finally {
            this.lock.unlock();
        }
    }
    // 可见pool中管理的是 routePool,每个route的是可复用的
    private RouteSpecificPool<T, C, E> getPool(final T route) {
        // 获取此 route 对应的 RouteSpecificPool
        RouteSpecificPool<T, C, E> pool = this.routeToPool.get(route);
        if (pool == null) {
            // 没有就创建一个,并复写了 createEntry 方法
            pool = new RouteSpecificPool<T, C, E>(route) {

                @Override
                protected E createEntry(final C conn) {
                    return AbstractConnPool.this.createEntry(route, conn);
                }

            };
            // 记录
            this.routeToPool.put(route, pool);
        }
        return pool;
    }

    private static Exception operationAborted() {
        return new CancellationException("Operation aborted");
    }

    /**
     * {@inheritDoc}
     * <p>
     * Please note that this class does not maintain its own pool of execution
     * {@link Thread}s. Therefore, one <b>must</b> call {@link Future#get()}
     * or {@link Future#get(long, TimeUnit)} method on the {@link Future}
     * returned by this method in order for the lease operation to complete.
     */
    // 从池中租用连接
    @Override
    public Future<E> lease(final T route, final Object state, final FutureCallback<E> callback) {
        Args.notNull(route, "Route");
        Asserts.check(!this.isShutDown, "Connection pool shut down");
        // 创建了一个 furture
        // 在future中进行具体的工作
        return new Future<E>() {
            // 是否可以取消
            private final AtomicBoolean cancelled = new AtomicBoolean(false);
            // 是否完成
            private final AtomicBoolean done = new AtomicBoolean(false);
            // 存储引用
            private final AtomicReference<E> entryRef = new AtomicReference<E>(null);
            // 取消操作
            @Override
            public boolean cancel(final boolean mayInterruptIfRunning) {
                if (done.compareAndSet(false, true)) {
                    cancelled.set(true);
                    lock.lock();
                    try {
                        condition.signalAll();
                    } finally {
                        lock.unlock();
                    }
                    if (callback != null) {
                        callback.cancelled();
                    }
                    return true;
                }
                return false;
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }

            @Override
            public boolean isDone() {
                return done.get();
            }
            // 获取一个future 结果
            @Override
            public E get() throws InterruptedException, ExecutionException {
                try {
                    return get(0L, TimeUnit.MILLISECONDS);
                } catch (final TimeoutException ex) {
                    throw new ExecutionException(ex);
                }
            }
            // 相当于获取连接
            @Override
            public E get(final long timeout, final TimeUnit timeUnit) throws InterruptedException, ExecutionException, TimeoutException {
                for (;;) {
                    synchronized (this) {
                        try {
                            // 先从entryRed获取,查看是否已经有
                            final E entry = entryRef.get();
                            if (entry != null) {
                                return entry;
                            }
                            if (done.get()) {
                                throw new ExecutionException(operationAborted());
                            }
                            // 从routePool获取连接
                            // 如果没有则创建,如果达到最大值,则等待
                            final E leasedEntry = getPoolEntryBlocking(route, state, timeout, timeUnit, this);
                            if (validateAfterInactivity > 0)  {
                                if (leasedEntry.getUpdated() + validateAfterInactivity <= System.currentTimeMillis()) {
                                    if (!validate(leasedEntry)) {
                                        leasedEntry.close();
                                        release(leasedEntry, false);
                                        continue;
                                    }
                                }
                            }
                            // 记录完成
                            if (done.compareAndSet(false, true)) {
                                // 并记录 获取到的 entry
                                entryRef.set(leasedEntry);
                                done.set(true);
                                // 扩展方法
                                onLease(leasedEntry);
                                // 如果有回调方法,则调用回调方法
                                if (callback != null) {
                                    callback.completed(leasedEntry);
                                }
                                return leasedEntry;
                            } else {
                                release(leasedEntry, true);
                                throw new ExecutionException(operationAborted());
                            }
                        } catch (final IOException ex) {
                            if (done.compareAndSet(false, true)) {
                                if (callback != null) {
                                    callback.failed(ex);
                                }
                            }
                            throw new ExecutionException(ex);
                        }
                    }
                }
            }

        };
    }

    /**
     * Attempts to lease a connection for the given route and with the given
     * state from the pool.
     * <p>
     * Please note that this class does not maintain its own pool of execution
     * {@link Thread}s. Therefore, one <b>must</b> call {@link Future#get()}
     * or {@link Future#get(long, TimeUnit)} method on the {@link Future}
     * returned by this method in order for the lease operation to complete.
     *
     * @param route route of the connection.
     * @param state arbitrary object that represents a particular state
     *  (usually a security principal or a unique token identifying
     *  the user whose credentials have been used while establishing the connection).
     *  May be {@code null}.
     * @return future for a leased pool entry.
     */
    public Future<E> lease(final T route, final Object state) {
        return lease(route, state, null);
    }
    // 阻塞的方式 获取一个 entryBlocking
    private E getPoolEntryBlocking(
            final T route, final Object state,
            final long timeout, final TimeUnit timeUnit,
            final Future<E> future) throws IOException, InterruptedException, ExecutionException, TimeoutException {

        Date deadline = null;
        if (timeout > 0) {
            deadline = new Date (System.currentTimeMillis() + timeUnit.toMillis(timeout));
        }
        this.lock.lock();
        try {
            // 从池中获取 RouteSpecificPool
            final RouteSpecificPool<T, C, E> pool = getPool(route);
            E entry;
            for (;;) {
                Asserts.check(!this.isShutDown, "Connection pool shut down");
                // 任务是否取消
                if (future.isCancelled()) {
                    throw new ExecutionException(operationAborted());
                }
                for (;;) {
                    // 从route池中获取一个 free的 entry
                    // 即pool中对应的是route 池,route池中对应了具体主机的entry,entry中包装了连接
                    entry = pool.getFree(state);
                    // 没有,则break,进入下面进行 创建
                    if (entry == null) {
                        break;
                    }
                    // 检测 entry是否过时
                    if (entry.isExpired(System.currentTimeMillis())) {
                        entry.close();
                    }
                    // 检测entry 是否关闭
                    // 关闭了 则移除此entry
                    if (entry.isClosed()) {
                        this.available.remove(entry);
                        pool.free(entry, false);
                    } else {
                        break;
                    }
                }
                if (entry != null) {
                    // 获取到了,则从 available中移除 entry
                    this.available.remove(entry);
                    // 加入到 leased
                    this.leased.add(entry);
                    onReuse(entry);
                    return entry;
                }

                // New connection is needed
                // 每一个route的最大连接数
                final int maxPerRoute = getMax(route);
                // Shrink the pool prior to allocating a new connection
                // 查看是否 已经超过了 最大连接数
                final int excess = Math.max(0, pool.getAllocatedCount() + 1 - maxPerRoute);
                // 如果超过了最大连接数,则 关闭一些连接
                if (excess > 0) {
                    // 如果超过了, 则释放一些连接
                    for (int i = 0; i < excess; i++) {
                        final E lastUsed = pool.getLastUsed();
                        if (lastUsed == null) {
                            break;
                        }
                        lastUsed.close();
                        this.available.remove(lastUsed);
                        pool.remove(lastUsed);
                    }
                }

                if (pool.getAllocatedCount() < maxPerRoute) {
                    // 租用的个数
                    final int totalUsed = this.leased.size();
                    // 可用的个数
                    final int freeCapacity = Math.max(this.maxTotal - totalUsed, 0);
                    // 如果可用的数量大于0
                    if (freeCapacity > 0) {
                        // 可用的个数
                        final int totalAvailable = this.available.size();
                        // 释放一些可用的连接
                        if (totalAvailable > freeCapacity - 1) {
                            if (!this.available.isEmpty()) {
                                // 移除上次使用的, 创建一个新的 routPool
                                final E lastUsed = this.available.removeLast();
                                lastUsed.close();
                                final RouteSpecificPool<T, C, E> otherpool = getPool(lastUsed.getRoute());
                                otherpool.remove(lastUsed);
                            }
                        }
                        // 创建连接, 创建socket 并进行了 connect
                        // ----- 重点--------
                        final C conn = this.connFactory.create(route);
                        // 记录创建的连接
                        entry = pool.add(conn);
                        // 记录此 entry
                        this.leased.add(entry);
                        // 返回创建的 entry
                        return entry;
                    }
                }

                boolean success = false;
                try {
                    // 记录此 没有获取到连接的 future
                    pool.queue(future);
                    // 记录
                    this.pending.add(future);
                    if (deadline != null) {
                        // 超时时间的等待
                        success = this.condition.awaitUntil(deadline);
                    } else {
                        // 持续等待
                        this.condition.await();
                        success = true;
                    }
                    if (future.isCancelled()) {
                        throw new ExecutionException(operationAborted());
                    }
                } finally {
                    // In case of 'success', we were woken up by the
                    // connection pool and should now have a connection
                    // waiting for us, or else we're shutting down.
                    // Just continue in the loop, both cases are checked.
                    pool.unqueue(future);
                    this.pending.remove(future);
                }
                // check for spurious wakeup vs. timeout
                if (!success && (deadline != null && deadline.getTime() <= System.currentTimeMillis())) {
                    break;
                }
            }
            throw new TimeoutException("Timeout waiting for connection");
        } finally {
            this.lock.unlock();
        }
    }
    // 释放连接 到 池中
    @Override
    public void release(final E entry, final boolean reusable) {
        this.lock.lock();
        try {
            // 从 租用容器中移除此 entry
            if (this.leased.remove(entry)) {
                // 获取此 route对应的 RouteSpecificPool
                final RouteSpecificPool<T, C, E> pool = getPool(entry.getRoute());
                // 把此 entry重新放回到 available
                pool.free(entry, reusable);
                if (reusable && !this.isShutDown) {
                    this.available.addFirst(entry);
                } else {
                    // 关闭连接 最终会关闭socket
                    entry.close();
                }
                // 扩展方法
                onRelease(entry);
                // 找到下一个 penging
                Future<E> future = pool.nextPending();
                if (future != null) {
                    this.pending.remove(future);
                } else {
                    future = this.pending.poll();
                }
                // 在此处进行唤醒操作
                if (future != null) {
                    this.condition.signalAll();
                }
            }
        } finally {
            this.lock.unlock();
        }
    }

    private int getMax(final T route) {
        final Integer v = this.maxPerRoute.get(route);
        return v != null ? v.intValue() : this.defaultMaxPerRoute;
    }

    @Override
    public void setMaxTotal(final int max) {
        Args.positive(max, "Max value");
        this.lock.lock();
        try {
            this.maxTotal = max;
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public int getMaxTotal() {
        this.lock.lock();
        try {
            return this.maxTotal;
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public void setDefaultMaxPerRoute(final int max) {
        Args.positive(max, "Max per route value");
        this.lock.lock();
        try {
            this.defaultMaxPerRoute = max;
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public int getDefaultMaxPerRoute() {
        this.lock.lock();
        try {
            return this.defaultMaxPerRoute;
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public void setMaxPerRoute(final T route, final int max) {
        Args.notNull(route, "Route");
        this.lock.lock();
        try {
            if (max > -1) {
                this.maxPerRoute.put(route, Integer.valueOf(max));
            } else {
                this.maxPerRoute.remove(route);
            }
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public int getMaxPerRoute(final T route) {
        Args.notNull(route, "Route");
        this.lock.lock();
        try {
            return getMax(route);
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public PoolStats getTotalStats() {
        this.lock.lock();
        try {
            return new PoolStats(
                    this.leased.size(),
                    this.pending.size(),
                    this.available.size(),
                    this.maxTotal);
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public PoolStats getStats(final T route) {
        Args.notNull(route, "Route");
        this.lock.lock();
        try {
            final RouteSpecificPool<T, C, E> pool = getPool(route);
            return new PoolStats(
                    pool.getLeasedCount(),
                    pool.getPendingCount(),
                    pool.getAvailableCount(),
                    getMax(route));
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Returns snapshot of all knows routes
     * @return the set of routes
     *
     * @since 4.4
     */
    public Set<T> getRoutes() {
        this.lock.lock();
        try {
            return new HashSet<T>(routeToPool.keySet());
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Enumerates all available connections.
     *
     * @since 4.3
     */
    // 遍历所有可用的连接, 查找其中过期的连接,过期则关闭
    protected void enumAvailable(final PoolEntryCallback<T, C> callback) {
        this.lock.lock();
        try {
            // 获取 可用连接的 迭代器
            final Iterator<E> it = this.available.iterator();
            while (it.hasNext()) {
                final E entry = it.next();
                callback.process(entry);
                if (entry.isClosed()) {
                    final RouteSpecificPool<T, C, E> pool = getPool(entry.getRoute());
                    pool.remove(entry);
                    it.remove();
                }
            }
            // 移除那些已经 没有连接的 routePool  entry
            purgePoolMap();
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Enumerates all leased connections.
     *
     * @since 4.3
     */
    protected void enumLeased(final PoolEntryCallback<T, C> callback) {
        this.lock.lock();
        try {
            final Iterator<E> it = this.leased.iterator();
            while (it.hasNext()) {
                final E entry = it.next();
                callback.process(entry);
            }
        } finally {
            this.lock.unlock();
        }
    }
    // 移除那些已经 没有连接的 routePool  entry
    private void purgePoolMap() {
        final Iterator<Map.Entry<T, RouteSpecificPool<T, C, E>>> it = this.routeToPool.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<T, RouteSpecificPool<T, C, E>> entry = it.next();
            final RouteSpecificPool<T, C, E> pool = entry.getValue();
            if (pool.getPendingCount() + pool.getAllocatedCount() == 0) {
                it.remove();
            }
        }
    }

    /**
     * Closes connections that have been idle longer than the given period
     * of time and evicts them from the pool.
     *
     * @param idletime maximum idle time.
     * @param timeUnit time unit.
     */
    // 关闭 idle的 连接
    public void closeIdle(final long idletime, final TimeUnit timeUnit) {
        Args.notNull(timeUnit, "Time unit");
        // 获取 idle 超时的时间
        long time = timeUnit.toMillis(idletime);
        // 如果超时时间 小于0, 那么设置为0
        if (time < 0) {
            time = 0;
        }
        // 获取deadline,即获取一个  超时前的时间,如果小于这个时间,那么说明此连接超过idle 时间没有更新了
        final long deadline = System.currentTimeMillis() - time;
        // 遍历所有的 可用的 连接, 移除那些idle 超时的 连接
        enumAvailable(new PoolEntryCallback<T, C>() {

            @Override
            public void process(final PoolEntry<T, C> entry) {
                if (entry.getUpdated() <= deadline) {
                    entry.close();
                }
            }

        });
    }

    /**
     * Closes expired connections and evicts them from the pool.
     */
    // 关闭过期的 连接
    public void closeExpired() {
        final long now = System.currentTimeMillis();
        enumAvailable(new PoolEntryCallback<T, C>() {

            @Override
            public void process(final PoolEntry<T, C> entry) {
                if (entry.isExpired(now)) {
                    entry.close();
                }
            }

        });
    }

    /**
     * @return the number of milliseconds
     * @since 4.4
     */
    public int getValidateAfterInactivity() {
        return this.validateAfterInactivity;
    }

    /**
     * @param ms the number of milliseconds
     * @since 4.4
     */
    public void setValidateAfterInactivity(final int ms) {
        this.validateAfterInactivity = ms;
    }

    @Override
    public String toString() {
        this.lock.lock();
        try {
            final StringBuilder buffer = new StringBuilder();
            buffer.append("[leased: ");
            buffer.append(this.leased);
            buffer.append("][available: ");
            buffer.append(this.available);
            buffer.append("][pending: ");
            buffer.append(this.pending);
            buffer.append("]");
            return buffer.toString();
        } finally {
            this.lock.unlock();
        }
    }

}
