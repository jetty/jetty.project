//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.nio.channels.SelectableChannel;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Connection.Listener;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A Listener that limits the number of Connections.</p>
 * <p>This listener applies a limit to the number of connections, which when
 * exceeded results in  a call to {@link AbstractConnector#setAccepting(boolean)}
 * to prevent further connections being received.
 * This listener can be applied to an entire {@link Server} or to a specific
 * {@link Connector} by adding it via {@link Container#addBean(Object)}.
 * </p>
 * <p>When the number of connections is exceeded, the idle timeout of existing
 * connections is changed with the value configured in this listener (typically
 * a shorter value).</p>
 * <p>
 * <b>Usage:</b>
 * </p>
 * <pre>{@code
 *   Server server = new Server();
 *   server.addBean(new ConnectionLimit(5000,server));
 *   ...
 *   server.start();
 * }</pre>
 *
 *
 * @see LowResourceMonitor
 * @see Connection.Listener
 * @see SelectorManager.AcceptListener
 * @deprecated use {@link NetworkConnectionLimit} instead
 */
@Deprecated(forRemoval = true, since = "12.1.0")
@ManagedObject
public class ConnectionLimit extends AbstractLifeCycle implements Listener, SelectorManager.AcceptListener
{
    private static final Logger LOG = LoggerFactory.getLogger(ConnectionLimit.class);

    private final AutoLock _lock = new AutoLock();
    private final Server _server;
    private final List<AbstractConnector> _connectors = new ArrayList<>();
    private int _accepting;
    private int _connections;
    private int _maxConnections;
    private long _idleTimeout;
    private boolean _limiting = false;

    public ConnectionLimit(@Name("maxConnections") int maxConnections, @Name("server") Server server)
    {
        _maxConnections = maxConnections;
        _server = server;
    }

    public ConnectionLimit(@Name("maxConnections") int maxConnections, @Name("connectors") Connector... connectors)
    {
        this(maxConnections, (Server)null);
        for (Connector c : connectors)
        {
            if (c instanceof AbstractConnector)
                _connectors.add((AbstractConnector)c);
            else
                LOG.warn("Connector {} is not an AbstractConnector: connections will not be limited", c);
        }
    }

    /**
     * @return the endpoint idle timeout in ms to apply when the connection limit is reached
     */
    @ManagedAttribute("The endpoint idle timeout in ms to apply when the connection limit is reached")
    public long getIdleTimeout()
    {
        return _idleTimeout;
    }

    /**
     * <p>Sets the endpoint idle timeout in ms to apply when the connection limit is reached.</p>
     * <p>A value less than or equal to zero will not change the existing idle timeout.</p>
     *
     * @param idleTimeout the endpoint idle timeout in ms to apply when the connection limit is reached
     */
    public void setIdleTimeout(long idleTimeout)
    {
        _idleTimeout = idleTimeout;
    }

    @ManagedAttribute("The maximum number of connections allowed")
    public int getMaxConnections()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _maxConnections;
        }
    }

    public void setMaxConnections(int max)
    {
        try (AutoLock ignored = _lock.lock())
        {
            _maxConnections = max;
        }
    }

    @ManagedAttribute(value = "The current number of connections", readonly = true)
    public int getConnections()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _connections;
        }
    }

    @ManagedAttribute(value = "The current number of pending connections", readonly = true)
    public int getPendingConnections()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _accepting;
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (_server != null)
            {
                for (Connector c : _server.getConnectors())
                {
                    if (c instanceof AbstractConnector)
                        _connectors.add((AbstractConnector)c);
                    else
                        LOG.warn("Connector {} is not an AbstractConnector. Connections not limited", c);
                }
            }
            if (LOG.isDebugEnabled())
                LOG.debug("Connection limit {} for {}", _maxConnections, _connectors);
            _connections = 0;
            _limiting = false;
            for (AbstractConnector c : _connectors)
            {
                c.addBean(this);
            }
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        try (AutoLock ignored = _lock.lock())
        {
            for (AbstractConnector c : _connectors)
            {
                c.removeBean(this);
            }
            _connections = 0;
            if (_server != null)
                _connectors.clear();
        }
    }

    private boolean check()
    {
        assert _lock.isHeldByCurrentThread();
        int total = _accepting + _connections;
        if (total >= _maxConnections)
        {
            if (!_limiting)
            {
                _limiting = true;
                LOG.info("Connection limit {} reached for {}", _maxConnections, _connectors);
                limit();
            }
            return total > _maxConnections;
        }
        else
        {
            if (_limiting)
            {
                _limiting = false;
                LOG.info("Connection limit {} cleared for {}", _maxConnections, _connectors);
                unlimit();
            }
            return false;
        }
    }

    protected void limit()
    {
        for (AbstractConnector c : _connectors)
        {
            c.setAccepting(false);

            if (_idleTimeout > 0)
            {
                for (EndPoint endPoint : c.getConnectedEndPoints())
                {
                    endPoint.setIdleTimeout(_idleTimeout);
                }
            }
        }
    }

    protected void unlimit()
    {
        for (AbstractConnector c : _connectors)
        {
            c.setAccepting(true);

            if (_idleTimeout > 0)
            {
                for (EndPoint endPoint : c.getConnectedEndPoints())
                {
                    endPoint.setIdleTimeout(c.getIdleTimeout());
                }
            }
        }
    }

    @Override
    public void onAccepting(SelectableChannel channel)
    {
        try (AutoLock ignored = _lock.lock())
        {
            _accepting++;
            if (LOG.isDebugEnabled())
                LOG.debug("Accepting ({}+{}) <= {} {}", _accepting, _connections, _maxConnections, channel);
            if (check())
                IO.close(channel);
        }
    }

    @Override
    public void onAcceptFailed(SelectableChannel channel, Throwable cause)
    {
        try (AutoLock ignored = _lock.lock())
        {
            _accepting--;
            if (LOG.isDebugEnabled())
                LOG.debug("Accept failed ({}+{}) <= {} {}", _accepting, _connections, _maxConnections, channel, cause);
            check();
        }
    }

    @Override
    public void onAccepted(SelectableChannel channel)
    {
    }

    @Override
    public void onOpened(Connection connection)
    {
        try (AutoLock ignored = _lock.lock())
        {
            _accepting--;
            _connections++;
            if (LOG.isDebugEnabled())
                LOG.debug("Opened ({}+{}) <= {} {}", _accepting, _connections, _maxConnections, connection);
            check();
        }
    }

    @Override
    public void onClosed(Connection connection)
    {
        try (AutoLock ignored = _lock.lock())
        {
            _connections--;
            if (LOG.isDebugEnabled())
                LOG.debug("Closed ({}+{}) <= {} {}", _accepting, _connections, _maxConnections, connection);
            check();
        }
    }
}
