//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

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
    private static final Logger LOG = Log.getLogger(ConnectionLimit.class);

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
        synchronized (this)
        {
            return _maxConnections;
        }
    }

    public void setMaxConnections(int max)
    {
        synchronized (this)
        {
            _maxConnections = max;
        }
    }

    @ManagedAttribute("The current number of connections ")
    public int getConnections()
    {
        synchronized (this)
        {
            return _connections;
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        synchronized (this)
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
        synchronized (this)
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
        synchronized (this)
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
        synchronized (this)
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
        synchronized (this)
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
        synchronized (this)
        {
            _connections--;
            if (LOG.isDebugEnabled())
                LOG.debug("onClosed ({}+{}) < {} {}", _accepting.size(), _connections, _maxConnections, connection);
            check();
        }
    }
}
