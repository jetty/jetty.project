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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A connection pool that validates connections before
 * making them available for use.</p>
 * <p>Connections that have just been opened are not validated.
 * Connections that are {@link #release(Connection) released} will
 * be validated.</p>
 * <p>Validation by reading from the EndPoint is not reliable,
 * since the TCP FIN may arrive just after the validation read.</p>
 * <p>This class validates connections by putting them in a
 * "quarantine" for a configurable timeout, where they cannot
 * be used to send requests. When the timeout expires, the
 * quarantined connection is made idle and therefore available
 * to send requests.</p>
 * <p>The existing HttpClient mechanism to detect server closes
 * will trigger and close quarantined connections, before they
 * are made idle (and reusable) again.</p>
 * <p>There still is a small chance that the timeout expires,
 * the connection is made idle and available again, it is used
 * to send a request exactly when the server decides to close.
 * This case is however unavoidable and may be mitigated by
 * tuning the idle timeout of the servers to be larger than
 * that of the client.</p>
 */
public class ValidatingConnectionPool extends DuplexConnectionPool
{
    private static final Logger LOG = LoggerFactory.getLogger(ValidatingConnectionPool.class);

    private final Scheduler scheduler;
    private final long timeout;
    private final Map<Connection, Holder> quarantine;

    public ValidatingConnectionPool(HttpDestination destination, int maxConnections, Callback requester, Scheduler scheduler, long timeout)
    {
        super(destination, maxConnections, requester);
        this.scheduler = scheduler;
        this.timeout = timeout;
        this.quarantine = new ConcurrentHashMap<>(maxConnections);
    }

    @ManagedAttribute(value = "The number of validating connections", readonly = true)
    public int getValidatingConnectionCount()
    {
        return quarantine.size();
    }

    @Override
    public boolean release(Connection connection)
    {
        Holder holder = new Holder(connection);
        holder.task = scheduler.schedule(holder, timeout, TimeUnit.MILLISECONDS);
        quarantine.put(connection, holder);
        if (LOG.isDebugEnabled())
            LOG.debug("Validating for {}ms {}", timeout, connection);

        released(connection);
        return true;
    }

    @Override
    public boolean remove(Connection connection)
    {
        Holder holder = quarantine.remove(connection);
        if (holder != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Removed while validating {}", connection);
            holder.cancel();
        }
        return super.remove(connection);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        DumpableCollection toDump = new DumpableCollection("quarantine", quarantine.values());
        Dumpable.dumpObjects(out, indent, this, toDump);
    }

    @Override
    public String toString()
    {
        int size = quarantine.size();
        return String.format("%s[v=%d]", super.toString(), size);
    }

    private class Holder implements Runnable
    {
        private final long timestamp = System.nanoTime();
        private final AtomicBoolean done = new AtomicBoolean();
        private final Connection connection;
        public Scheduler.Task task;

        public Holder(Connection connection)
        {
            this.connection = connection;
        }

        @Override
        public void run()
        {
            if (done.compareAndSet(false, true))
            {
                boolean closed = isClosed();
                if (LOG.isDebugEnabled())
                    LOG.debug("Validated {}", connection);
                quarantine.remove(connection);
                if (!closed)
                    deactivate(connection);
                idle(connection, closed);
                proceed();
            }
        }

        public boolean cancel()
        {
            if (done.compareAndSet(false, true))
            {
                task.cancel();
                return true;
            }
            return false;
        }

        @Override
        public String toString()
        {
            return String.format("%s[validationLeft=%dms]",
                connection,
                timeout - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - timestamp)
            );
        }
    }
}
