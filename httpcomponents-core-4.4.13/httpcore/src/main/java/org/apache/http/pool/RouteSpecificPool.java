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

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.Future;

import org.apache.http.util.Args;
import org.apache.http.util.Asserts;

abstract class RouteSpecificPool<T, C, E extends PoolEntry<T, C>> {

    private final T route;
    // 租用的 连接
    private final Set<E> leased;
    // 可用的连接
    private final LinkedList<E> available;
    // 等待中的
    private final LinkedList<Future<E>> pending;

    RouteSpecificPool(final T route) {
        super();
        this.route = route;
        this.leased = new HashSet<E>();
        this.available = new LinkedList<E>();
        this.pending = new LinkedList<Future<E>>();
    }

    protected abstract E createEntry(C conn);

    public final T getRoute() {
        return route;
    }

    public int getLeasedCount() {
        return this.leased.size();
    }

    public int getPendingCount() {
        return this.pending.size();
    }

    public int getAvailableCount() {
        return this.available.size();
    }

    public int getAllocatedCount() {
        return this.available.size() + this.leased.size();
    }
    // 从此 route pool中获取一个entry
    public E getFree(final Object state) {
        if (!this.available.isEmpty()) {
            if (state != null) {
                final Iterator<E> it = this.available.iterator();
                while (it.hasNext()) {
                    final E entry = it.next();
                    if (state.equals(entry.getState())) {
                        it.remove();
                        this.leased.add(entry);
                        return entry;
                    }
                }
            }
            final Iterator<E> it = this.available.iterator();
            while (it.hasNext()) {
                final E entry = it.next();
                if (entry.getState() == null) {
                    it.remove();
                    this.leased.add(entry);
                    return entry;
                }
            }
        }
        return null;
    }

    public E getLastUsed() {
        return this.available.isEmpty() ? null : this.available.getLast();
    }
    // 从 routePool 中移除 entry
    public boolean remove(final E entry) {
        Args.notNull(entry, "Pool entry");
        if (!this.available.remove(entry)) {
            if (!this.leased.remove(entry)) {
                return false;
            }
        }
        return true;
    }
    // 回收此route的连接
    public void free(final E entry, final boolean reusable) {
        Args.notNull(entry, "Pool entry");
        // 从 租用容器中删除
        final boolean found = this.leased.remove(entry);
        Asserts.check(found, "Entry %s has not been leased from this pool", entry);
        // 如果可重用,则 添加到 available
        if (reusable) {
            this.available.addFirst(entry);
        }
    }
    // 添加一个 连接到此池中
    public E add(final C conn) {
        final E entry = createEntry(conn);
        this.leased.add(entry);
        return entry;
    }
    // 记录那些没有获取到连接的 future
    // 当有连接回收到,再唤醒这些future
    public void queue(final Future<E> future) {
        if (future == null) {
            return;
        }
        this.pending.add(future);
    }

    public Future<E> nextPending() {
        return this.pending.poll();
    }

    public void unqueue(final Future<E> future) {
        if (future == null) {
            return;
        }

        this.pending.remove(future);
    }
    // routePool 进行关闭
    public void shutdown() {
        // 取消 pending
        for (final Future<E> future: this.pending) {
            future.cancel(true);
        }
        this.pending.clear();
        // 可用连接 关闭
        for (final E entry: this.available) {
            entry.close();
        }
        // 情况容器
        this.available.clear();
        // 租用连接关闭
        for (final E entry: this.leased) {
            entry.close();
        }
        // 租用容器清空
        this.leased.clear();
    }

    @Override
    public String toString() {
        final StringBuilder buffer = new StringBuilder();
        buffer.append("[route: ");
        buffer.append(this.route);
        buffer.append("][leased: ");
        buffer.append(this.leased.size());
        buffer.append("][available: ");
        buffer.append(this.available.size());
        buffer.append("][pending: ");
        buffer.append(this.pending.size());
        buffer.append("]");
        return buffer.toString();
    }

}
