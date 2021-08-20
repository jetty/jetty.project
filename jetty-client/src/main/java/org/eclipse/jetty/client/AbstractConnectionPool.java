//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
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

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.util.Attachable;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Sweeper;

import static java.util.stream.Collectors.toCollection;

@ManagedObject
public abstract class AbstractConnectionPool extends ContainerLifeCycle implements ConnectionPool, Dumpable, Sweeper.Sweepable
{
    private static final Logger LOG = Log.getLogger(AbstractConnectionPool.class);

    private final AtomicInteger pending = new AtomicInteger();
    private final HttpDestination destination;
    private final Callback requester;
    private final Pool<Connection> pool;
    private boolean maximizeConnections;
    private volatile long maxDurationNanos = 0L;

    /**
     * @deprecated use {@link #AbstractConnectionPool(HttpDestination, int, boolean, Callback)} instead
     */
    @Deprecated
    protected AbstractConnectionPool(Destination destination, int maxConnections, Callback requester)
    {
        this((HttpDestination)destination, maxConnections, false, requester);
    }

    protected AbstractConnectionPool(HttpDestination destination, int maxConnections, boolean cache, Callback requester)
    {
        this(destination, new Pool<>(Pool.StrategyType.FIRST, maxConnections, cache), requester);
    }

    protected AbstractConnectionPool(HttpDestination destination, Pool<Connection> pool, Callback requester)
    {
        this.destination = destination;
        this.requester = requester;
        this.pool = pool;
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
     * <p>Get the max usage duration in milliseconds of the pool's connections.
     * Values {@code 0} and negative mean that there is no limit.</p>
     * <p>This only guarantees that a connection cannot be acquired after the configured
     * duration elapses, so that is only enforced when {@link #acquire()} is called.
     * If a pool stays completely idle for a duration longer than the value
     * returned by this method, the max duration will not be enforced.
     * It's up to the idle timeout mechanism (see {@link HttpClient#getIdleTimeout()})
     * to handle closing idle connections.</p>
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

    protected int getMaxMultiplex()
    {
        return pool.getMaxMultiplex();
    }

    protected void setMaxMultiplex(int maxMultiplex)
    {
        pool.setMaxMultiplex(maxMultiplex);
    }

    protected int getMaxUsageCount()
    {
        return pool.getMaxUsageCount();
    }

    protected void setMaxUsageCount(int maxUsageCount)
    {
        pool.setMaxUsageCount(maxUsageCount);
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

    /**
     * @return the number of pending connections
     * @deprecated use {@link #getPendingConnectionCount()} instead
     */
    @ManagedAttribute(value = "The number of pending connections", readonly = true)
    @Deprecated
    public int getPendingCount()
    {
        return getPendingConnectionCount();
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

    @Override
    public Connection acquire()
    {
        return acquire(true);
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
    protected Connection acquire(boolean create)
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
     * {@link #getMaxMultiplex()} factor: if the demand is less than the supply, the
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
        int multiplexed = getMaxMultiplex();
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
            return;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Creating connection {}/{} at {}", connectionCount, getMaxConnectionCount(), entry);
        Promise<Connection> future = new FutureConnection(entry);
        destination.newConnection(future);
    }

    protected void proceed()
    {
        requester.succeeded();
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
                        boolean canClose = remove(connection, true);
                        if (canClose)
                            IO.close(connection);
                        if (LOG.isDebugEnabled())
                            LOG.debug("Connection removed{} due to expiration {} {}", (canClose ? " and closed" : ""), entry, pool);
                        continue;
                    }
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
        if (!(connection instanceof Attachable))
            throw new IllegalArgumentException("Invalid connection object: " + connection);
        Attachable attachable = (Attachable)connection;
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
        if (!(connection instanceof Attachable))
            throw new IllegalArgumentException("Invalid connection object: " + connection);
        Attachable attachable = (Attachable)connection;
        EntryHolder holder = (EntryHolder)attachable.getAttachment();
        if (holder == null)
            return true;
        boolean reusable = pool.release(holder.entry);
        if (LOG.isDebugEnabled())
            LOG.debug("Released ({}) {} {}", reusable, holder.entry, pool);
        if (reusable)
            return true;
        remove(connection);
        return false;
    }

    @Override
    public boolean remove(Connection connection)
    {
        return remove(connection, false);
    }

    protected boolean remove(Connection connection, boolean force)
    {
        if (!(connection instanceof Attachable))
            throw new IllegalArgumentException("Invalid connection object: " + connection);
        Attachable attachable = (Attachable)connection;
        EntryHolder holder = (EntryHolder)attachable.getAttachment();
        if (holder == null)
            return false;
        boolean removed = pool.remove(holder.entry);
        if (removed)
            attachable.setAttachment(null);
        if (LOG.isDebugEnabled())
            LOG.debug("Removed ({}) {} {}", removed, holder.entry, pool);
        if (removed || force)
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

    /**
     * @return an unmodifiable queue working as a view of the idle connections.
     * @deprecated Relying on this method indicates a reliance on the implementation details.
     */
    @Deprecated
    public Queue<Connection> getIdleConnections()
    {
        return pool.values().stream()
            .filter(Pool.Entry::isIdle)
            .filter(entry -> !entry.isClosed())
            .map(Pool.Entry::getPooled)
            .collect(toCollection(ArrayDeque::new));
    }

    /**
     * @return an unmodifiable collection working as a view of the active connections.
     * @deprecated Relying on this method indicates a reliance on the implementation details.
     */
    @Deprecated
    public Collection<Connection> getActiveConnections()
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
        return String.format("%s@%x[c=%d/%d/%d,a=%d,i=%d,q=%d]",
            getClass().getSimpleName(),
            hashCode(),
            getPendingConnectionCount(),
            getConnectionCount(),
            getMaxConnectionCount(),
            getActiveConnectionCount(),
            getIdleConnectionCount(),
            destination.getQueuedRequestCount());
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
            requester.failed(x);
        }
    }

    private static class EntryHolder
    {
        private final Pool<Connection>.Entry entry;
        private final long creationTimestamp = System.nanoTime();

        private EntryHolder(Pool<Connection>.Entry entry)
        {
            this.entry = Objects.requireNonNull(entry);
        }

        private boolean isExpired(long timeoutNanos)
        {
            return System.nanoTime() - creationTimestamp >= timeoutNanos;
        }
    }
}
