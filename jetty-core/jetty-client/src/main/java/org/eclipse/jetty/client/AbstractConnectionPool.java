//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.eclipse.jetty.client.internal.HttpDestination;
import org.eclipse.jetty.util.Attachable;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.thread.Sweeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.stream.Collectors.toCollection;

@ManagedObject
public abstract class AbstractConnectionPool extends ContainerLifeCycle implements ConnectionPool, Dumpable, Sweeper.Sweepable
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractConnectionPool.class);

    private final AtomicInteger pending = new AtomicInteger();
    private final HttpDestination destination;
    private final Pool<Connection> pool;
    private boolean maximizeConnections;
    private volatile long maxDurationNanos;
    private volatile int maxUsage;
    private volatile int initialMaxMultiplex;

    protected AbstractConnectionPool(Destination destination, Pool<Connection> pool, int initialMaxMultiplex)
    {
        this.destination = (HttpDestination)destination;
        this.pool = pool;
        this.initialMaxMultiplex = initialMaxMultiplex;
        addBean(pool);
    }

    @Override
    protected void doStop() throws Exception
    {
        pool.close();
    }

    @Override
    public CompletableFuture<Void> preCreateConnections(int connectionCount)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Pre-creating connections {}/{}", connectionCount, getMaxConnectionCount());

        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int i = 0; i < connectionCount; i++)
        {
            Pool<Connection>.Entry entry = pool.reserve();
            if (entry == null)
                break;
            pending.incrementAndGet();
            Promise.Completable<Connection> future = new FutureConnection(entry);
            futures.add(future);
            if (LOG.isDebugEnabled())
                LOG.debug("Pre-creating connection {}/{} at {}", futures.size(), getMaxConnectionCount(), entry);
            destination.newConnection(future);
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * <p>Returns the max usage duration in milliseconds of a pooled connection.</p>
     * <p>Values {@code 0} and negative mean that there is no limit.</p>
     * <p>This property only guarantees that a connection cannot be acquired
     * after the configured duration elapses, so that is only enforced when
     * {@link #acquire(boolean)} is called.</p>
     * <p>If a pool stays completely idle for a duration longer than the value
     * returned by this method, the max duration will not be enforced.
     * It's up to the idle timeout mechanism (see {@link HttpClient#getIdleTimeout()})
     * to handle closing idle connections.</p>
     *
     * @return the max usage duration, in milliseconds, of a pooled connection
     */
    @ManagedAttribute(value = "The maximum duration in milliseconds a connection can be used for before it gets closed")
    public long getMaxDuration()
    {
        return TimeUnit.NANOSECONDS.toMillis(maxDurationNanos);
    }

    public void setMaxDuration(long maxDurationInMs)
    {
        this.maxDurationNanos = TimeUnit.MILLISECONDS.toNanos(maxDurationInMs);
    }

    protected int getInitialMaxMultiplex()
    {
        return initialMaxMultiplex;
    }

    protected void setInitialMaxMultiplex(int initialMaxMultiplex)
    {
        this.initialMaxMultiplex = initialMaxMultiplex;
    }

    /**
     * <p>Returns the max number of times a pooled connection can be used.</p>
     * <p>Values {@code 0} and negative mean that there is no limit.</p>
     *
     * @return the max number of times a pooled connection can be used
     */
    @ManagedAttribute(value = "The maximum amount of times a connection is used before it gets closed")
    public int getMaxUsage()
    {
        return maxUsage;
    }

    public void setMaxUsage(int maxUsage)
    {
        this.maxUsage = maxUsage;
    }

    @ManagedAttribute(value = "The number of active connections", readonly = true)
    public int getActiveConnectionCount()
    {
        return pool.getInUseCount();
    }

    @ManagedAttribute(value = "The number of idle connections", readonly = true)
    public int getIdleConnectionCount()
    {
        return pool.getIdleCount();
    }

    @ManagedAttribute(value = "The max number of connections", readonly = true)
    public int getMaxConnectionCount()
    {
        return pool.getMaxEntries();
    }

    @ManagedAttribute(value = "The number of connections", readonly = true)
    public int getConnectionCount()
    {
        return pool.size();
    }

    @ManagedAttribute(value = "The number of pending connections", readonly = true)
    public int getPendingConnectionCount()
    {
        return pending.get();
    }

    @Override
    public boolean isEmpty()
    {
        return pool.size() == 0;
    }

    @Override
    @ManagedAttribute("Whether this pool is closed")
    public boolean isClosed()
    {
        return pool.isClosed();
    }

    @ManagedAttribute("Whether the pool tries to maximize the number of connections used")
    public boolean isMaximizeConnections()
    {
        return maximizeConnections;
    }

    /**
     * <p>Sets whether the number of connections should be maximized.</p>
     *
     * @param maximizeConnections whether the number of connections should be maximized
     */
    public void setMaximizeConnections(boolean maximizeConnections)
    {
        this.maximizeConnections = maximizeConnections;
    }

    /**
     * <p>Returns an idle connection, if available;
     * if an idle connection is not available, and the given {@code create} parameter is {@code true}
     * or {@link #isMaximizeConnections()} is {@code true},
     * then attempts to open a new connection, if possible within the configuration of this
     * connection pool (for example, if it does not exceed the max connection count);
     * otherwise it attempts to open a new connection, if the number of queued requests is
     * greater than the number of pending connections;
     * if no connection is available even after the attempts to open, return {@code null}.</p>
     * <p>The {@code create} parameter is just a hint: the connection may be created even if
     * {@code false}, or may not be created even if {@code true}.</p>
     *
     * @param create a hint to attempt to open a new connection if no idle connections are available
     * @return an idle connection or {@code null} if no idle connections are available
     * @see #tryCreate(boolean)
     */
    @Override
    public Connection acquire(boolean create)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Acquiring create={} on {}", create, this);
        Connection connection = activate();
        if (connection == null)
        {
            tryCreate(create);
            connection = activate();
        }
        return connection;
    }

    /**
     * <p>Tries to create a new connection.</p>
     * <p>Whether a new connection is created is determined by the {@code create} parameter
     * and a count of demand and supply, where the demand is derived from the number of
     * queued requests, and the supply is the number of pending connections time the
     * {@link #getInitialMaxMultiplex()} factor: if the demand is less than the supply, the
     * connection will not be created.</p>
     * <p>Since the number of queued requests used to derive the demand may be a stale
     * value, it is possible that few more connections than strictly necessary may be
     * created, but enough to satisfy the demand.</p>
     *
     * @param create a hint to request to create a connection
     */
    protected void tryCreate(boolean create)
    {
        int connectionCount = getConnectionCount();
        if (LOG.isDebugEnabled())
            LOG.debug("Try creating connection {}/{} with {} pending", connectionCount, getMaxConnectionCount(), getPendingConnectionCount());

        // If we have already pending sufficient multiplexed connections, then do not create another.
        int multiplexed = getInitialMaxMultiplex();
        while (true)
        {
            int pending = this.pending.get();
            int supply = pending * multiplexed;
            int demand = destination.getQueuedRequestCount() + (create ? 1 : 0);

            boolean tryCreate = isMaximizeConnections() || supply < demand;

            if (LOG.isDebugEnabled())
                LOG.debug("Try creating({}) connection, pending/demand/supply: {}/{}/{}, result={}", create, pending, demand, supply, tryCreate);

            if (!tryCreate)
                return;

            if (this.pending.compareAndSet(pending, pending + 1))
                break;
        }

        // Create the connection.
        Pool<Connection>.Entry entry = pool.reserve();
        if (entry == null)
        {
            pending.decrementAndGet();
            if (LOG.isDebugEnabled())
                LOG.debug("Not creating connection as pool {} is full, pending: {}", pool, pending);
            return;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Creating connection {}/{} at {}", connectionCount, getMaxConnectionCount(), entry);
        Promise<Connection> future = new FutureConnection(entry);
        destination.newConnection(future);
    }

    @Override
    public boolean accept(Connection connection)
    {
        if (!(connection instanceof Attachable attachable))
            throw new IllegalArgumentException("Invalid connection object: " + connection);
        Pool<Connection>.Entry entry = pool.reserve();
        if (entry == null)
            return false;
        if (LOG.isDebugEnabled())
            LOG.debug("onCreating {} {}", entry, connection);
        attachable.setAttachment(new EntryHolder(entry));
        onCreated(connection);
        entry.enable(connection, false);
        idle(connection, false);
        return true;
    }

    protected void proceed()
    {
        destination.succeeded();
    }

    protected Connection activate()
    {
        while (true)
        {
            Pool<Connection>.Entry entry = pool.acquire();
            if (entry != null)
            {
                Connection connection = entry.getPooled();

                long maxDurationNanos = this.maxDurationNanos;
                if (maxDurationNanos > 0L)
                {
                    EntryHolder holder = (EntryHolder)((Attachable)connection).getAttachment();
                    if (holder.isExpired(maxDurationNanos))
                    {
                        boolean canClose = remove(connection);
                        if (canClose)
                            IO.close(connection);
                        if (LOG.isDebugEnabled())
                            LOG.debug("Connection removed{}: expired {} {}", (canClose ? " and closed" : ""), entry, pool);
                        continue;
                    }
                }

                int maxUsage = getMaxUsage();
                if (connection instanceof MaxUsable maxUsable)
                    maxUsage = maxUsable.getMaxUsage();
                if (maxUsage > 0)
                {
                    EntryHolder holder = (EntryHolder)((Attachable)connection).getAttachment();
                    if (!holder.use(maxUsage))
                    {
                        boolean canClose = remove(connection);
                        if (canClose)
                            IO.close(connection);
                        if (LOG.isDebugEnabled())
                            LOG.debug("Connection removed{}: over used {} {}", (canClose ? " and closed" : ""), entry, pool);
                        continue;
                    }
                    // Entry is now used, so it must be acquired.
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("Activated {} {}", entry, pool);
                acquired(connection);
                return connection;
            }
            return null;
        }
    }

    @Override
    public boolean isActive(Connection connection)
    {
        if (!(connection instanceof Attachable attachable))
            throw new IllegalArgumentException("Invalid connection object: " + connection);
        EntryHolder holder = (EntryHolder)attachable.getAttachment();
        if (holder == null)
            return false;
        return !holder.entry.isIdle();
    }

    @Override
    public boolean release(Connection connection)
    {
        if (!deactivate(connection))
            return false;
        released(connection);
        return idle(connection, isClosed());
    }

    protected boolean deactivate(Connection connection)
    {
        if (!(connection instanceof Attachable attachable))
            throw new IllegalArgumentException("Invalid connection object: " + connection);
        EntryHolder holder = (EntryHolder)attachable.getAttachment();
        if (holder == null)
            return true;

        long maxDurationNanos = this.maxDurationNanos;
        if (maxDurationNanos > 0L && holder.isExpired(maxDurationNanos))
        {
            // Remove instead of release if the connection expired.
            return !remove(connection);
        }

        int maxUsage = getMaxUsage();
        if (connection instanceof MaxUsable maxUsable)
            maxUsage = maxUsable.getMaxUsage();
        if (maxUsage > 0 && holder.isOverUsed(maxUsage))
        {
            // Remove instead of release if the connection is overused.
            return !remove(connection);
        }

        boolean reusable = holder.entry.release();
        if (LOG.isDebugEnabled())
            LOG.debug("Released ({}) {} {}", reusable, holder.entry, pool);
        if (reusable)
            return true;
        return !remove(connection);
    }

    @Override
    public boolean remove(Connection connection)
    {
        if (!(connection instanceof Attachable attachable))
            throw new IllegalArgumentException("Invalid connection object: " + connection);
        EntryHolder holder = (EntryHolder)attachable.getAttachment();
        if (holder == null)
            return false;
        boolean removed = holder.entry.remove();
        if (removed)
            attachable.setAttachment(null);
        if (LOG.isDebugEnabled())
            LOG.debug("Removed ({}) {} {}", removed, holder.entry, pool);
        if (removed)
        {
            released(connection);
            removed(connection);
        }
        return removed;
    }

    protected void onCreated(Connection connection)
    {
    }

    protected boolean idle(Connection connection, boolean close)
    {
        return !close;
    }

    protected void acquired(Connection connection)
    {
    }

    protected void released(Connection connection)
    {
    }

    protected void removed(Connection connection)
    {
    }

    Queue<Connection> getIdleConnections()
    {
        return pool.values().stream()
            .filter(Pool.Entry::isIdle)
            .filter(entry -> !entry.isClosed())
            .map(Pool.Entry::getPooled)
            .collect(toCollection(ArrayDeque::new));
    }

    Collection<Connection> getActiveConnections()
    {
        return pool.values().stream()
            .filter(entry -> !entry.isIdle())
            .filter(entry -> !entry.isClosed())
            .map(Pool.Entry::getPooled)
            .collect(Collectors.toList());
    }

    @Override
    public void close()
    {
        // Forcibly release and remove entries to do our best effort calling the listeners.
        try
        {
            for (Pool<Connection>.Entry entry : pool.values())
            {
                while (entry.isInUse())
                {
                    if (entry.release())
                    {
                        released(entry.getPooled());
                        break;
                    }
                }
                if (entry.remove())
                    removed(entry.getPooled());
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Detected concurrent modification while forcibly releasing the pooled connections", x);
            // We could not call the listeners for all entries, but at least the following
            // pool.close() call will still release all resources.
        }
        pool.close();
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this);
    }

    @Override
    public boolean sweep()
    {
        pool.values().stream()
            .map(Pool.Entry::getPooled)
            .filter(connection -> connection instanceof Sweeper.Sweepable)
            .forEach(connection ->
            {
                if (((Sweeper.Sweepable)connection).sweep())
                {
                    boolean removed = remove(connection);
                    LOG.warn("Connection swept: {}{}{} from active connections{}{}",
                        connection,
                        System.lineSeparator(),
                        removed ? "Removed" : "Not removed",
                        System.lineSeparator(),
                        dump());
                }
            });
        return false;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[s=%s,c=%d/%d/%d,a=%d,i=%d,q=%d,p=%s]",
            getClass().getSimpleName(),
            hashCode(),
            getState(),
            getPendingConnectionCount(),
            getConnectionCount(),
            getMaxConnectionCount(),
            getActiveConnectionCount(),
            getIdleConnectionCount(),
            destination.getQueuedRequestCount(),
            pool);
    }

    private class FutureConnection extends Promise.Completable<Connection>
    {
        private final Pool<Connection>.Entry reserved;

        public FutureConnection(Pool<Connection>.Entry reserved)
        {
            this.reserved = reserved;
        }

        @Override
        public void succeeded(Connection connection)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Connection creation succeeded {}: {}", reserved, connection);
            if (connection instanceof Attachable)
            {
                ((Attachable)connection).setAttachment(new EntryHolder(reserved));
                onCreated(connection);
                pending.decrementAndGet();
                reserved.enable(connection, false);
                idle(connection, false);
                complete(null);
                proceed();
            }
            else
            {
                // reduce pending on failure and if not multiplexing also reduce demand
                failed(new IllegalArgumentException("Invalid connection object: " + connection));
            }
        }

        @Override
        public void failed(Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Connection creation failed {}", reserved, x);
            // reduce pending on failure and if not multiplexing also reduce demand
            pending.decrementAndGet();
            reserved.remove();
            completeExceptionally(x);
            destination.failed(x);
        }
    }

    private static class EntryHolder
    {
        private final Pool<Connection>.Entry entry;
        private final long creationNanoTime = NanoTime.now();
        private final AtomicInteger usage = new AtomicInteger();

        private EntryHolder(Pool<Connection>.Entry entry)
        {
            this.entry = Objects.requireNonNull(entry);
        }

        private boolean isExpired(long timeoutNanos)
        {
            return NanoTime.since(creationNanoTime) >= timeoutNanos;
        }

        private boolean use(int maxUsage)
        {
            while (true)
            {
                int current = usage.get();
                if (current >= maxUsage)
                    return false;
                if (usage.compareAndSet(current, current + 1))
                    return true;
            }
        }

        public boolean isOverUsed(int maxUsage)
        {
            return usage.get() >= maxUsage;
        }
    }
}
