//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Connection.Listener;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.SelectorManager;
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
 * to prevent further connections being received.  It can be applied to an
 * entire server or to a specific connector by adding it via {@link Container#addBean(Object)}
 * </p>
 * <p>
 * <b>Usage:</b>
 * </p>
 * <pre>
 *   Server server = new Server();
 *   server.addBean(new ConnectionLimit(5000,server));
 *   ...
 *   server.start();
 * </pre>
 *
 * @see LowResourceMonitor
 * @see Connection.Listener
 * @see SelectorManager.AcceptListener
 */
@ManagedObject
public class ConnectionLimit extends AbstractLifeCycle implements Listener, SelectorManager.AcceptListener
{
    private static final Logger LOG = LoggerFactory.getLogger(ConnectionLimit.class);

    private final AutoLock _lock = new AutoLock();
    private final Server _server;
    private final List<AbstractConnector> _connectors = new ArrayList<>();
    private final Set<SelectableChannel> _accepting = new HashSet<>();
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
                LOG.warn("Connector {} is not an AbstractConnection. Connections not limited", c);
        }
    }

    /**
     * @return If &gt;= 0, the endpoint idle timeout in ms to apply when the connection limit is reached
     */
    @ManagedAttribute("The endpoint idle timeout in ms to apply when the connection limit is reached")
    public long getIdleTimeout()
    {
        return _idleTimeout;
    }

    /**
     * @param idleTimeout If &gt;= 0 the endpoint idle timeout in ms to apply when the connection limit is reached
     */
    public void setIdleTimeout(long idleTimeout)
    {
        _idleTimeout = idleTimeout;
    }

    @ManagedAttribute("The maximum number of connections allowed")
    public int getMaxConnections()
    {
        try (AutoLock l = _lock.lock())
        {
            return _maxConnections;
        }
    }

    public void setMaxConnections(int max)
    {
        try (AutoLock l = _lock.lock())
        {
            _maxConnections = max;
        }
    }

    @ManagedAttribute("The current number of connections ")
    public int getConnections()
    {
        try (AutoLock l = _lock.lock())
        {
            return _connections;
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        try (AutoLock l = _lock.lock())
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
                LOG.debug("ConnectionLimit {} for {}", _maxConnections, _connectors);
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
        try (AutoLock l = _lock.lock())
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

    protected void check()
    {
        if ((_accepting.size() + _connections) >= _maxConnections)
        {
            if (!_limiting)
            {
                _limiting = true;
                LOG.info("Connection Limit({}) reached for {}", _maxConnections, _connectors);
                limit();
            }
        }
        else
        {
            if (_limiting)
            {
                _limiting = false;
                LOG.info("Connection Limit({}) cleared for {}", _maxConnections, _connectors);
                unlimit();
            }
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
        try (AutoLock l = _lock.lock())
        {
            _accepting.add(channel);
            if (LOG.isDebugEnabled())
                LOG.debug("onAccepting ({}+{}) < {} {}", _accepting.size(), _connections, _maxConnections, channel);
            check();
        }
    }

    @Override
    public void onAcceptFailed(SelectableChannel channel, Throwable cause)
    {
        try (AutoLock l = _lock.lock())
        {
            _accepting.remove(channel);
            if (LOG.isDebugEnabled())
                LOG.debug("onAcceptFailed ({}+{}) < {} {} {}", _accepting.size(), _connections, _maxConnections, channel, cause);
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
        try (AutoLock l = _lock.lock())
        {
            _accepting.remove(connection.getEndPoint().getTransport());
            _connections++;
            if (LOG.isDebugEnabled())
                LOG.debug("onOpened ({}+{}) < {} {}", _accepting.size(), _connections, _maxConnections, connection);
            check();
        }
    }

    @Override
    public void onClosed(Connection connection)
    {
        try (AutoLock l = _lock.lock())
        {
            _connections--;
            if (LOG.isDebugEnabled())
                LOG.debug("onClosed ({}+{}) < {} {}", _accepting.size(), _connections, _maxConnections, connection);
            check();
        }
    }
}
