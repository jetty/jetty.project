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

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A listener that limits the number of network connections.</p>
 * <p>This listener applies a limit to the number of network connections, which when
 * exceeded results in a call to {@link AbstractConnector#setAccepting(boolean)}
 * to prevent further network connections to be accepted.</p>
 * <p>This listener can be applied to an entire {@link Server} or to a specific
 * {@link Connector} by adding it via {@link Container#addBean(Object)}.</p>
 * <p>When the number of network connections is exceeded, the idle timeout of existing
 * {@code EndPoint}s is changed to the value configured in this listener (typically
 * a shorter value).
 * When the number of network connections returns below the limit, as they
 * are closed, the idle timeout of existing {@code EndPoint}s is restored
 * to that of the connector.</p>
 * <p>Typical usage:</p>
 * <pre>{@code
 * Server server = new Server();
 * server.addBean(new NetworkConnectionLimit(5000, server));
 * ...
 * server.start();
 * }</pre>
 *
 * @see LowResourceMonitor
 * @see SelectorManager.AcceptListener
 */
public class NetworkConnectionLimit extends AbstractLifeCycle implements SelectorManager.AcceptListener
{
    private static final Logger LOG = LoggerFactory.getLogger(NetworkConnectionLimit.class);

    private final AutoLock _lock = new AutoLock();
    private final Server _server;
    private final List<AbstractConnector> _connectors = new ArrayList<>();
    private int _pendingConnections;
    private int _connections;
    private int _maxNetworkConnections;
    private long _endPointIdleTimeout;
    private boolean _limiting;

    public NetworkConnectionLimit(@Name("maxNetworkConnectionCount") int maxNetworkConnections, @Name("server") Server server)
    {
        _maxNetworkConnections = maxNetworkConnections;
        _server = server;
    }

    public NetworkConnectionLimit(@Name("maxNetworkConnectionCount") int maxNetworkConnections, @Name("connectors") Connector... connectors)
    {
        this(maxNetworkConnections, (Server)null);
        registerConnectors(connectors);
    }

    private void registerConnectors(Connector[] connectors)
    {
        for (Connector c : connectors)
        {
            if (c instanceof AbstractConnector)
                _connectors.add((AbstractConnector)c);
            else
                LOG.warn("Connector {} is not an instance of {}: network connections will not be limited", c, AbstractConnector.class.getSimpleName());
        }
    }

    /**
     * @return the idle timeout in ms to apply to all EndPoints when the network connection limit is reached
     */
    @ManagedAttribute("The EndPoint idle timeout in ms to apply when the network connection limit is reached")
    public long getEndPointIdleTimeout()
    {
        return _endPointIdleTimeout;
    }

    /**
     * <p>Sets the idle timeout in ms to apply to all EndPoints when the network connection limit is reached.</p>
     * <p>A value less than or equal to zero will not change the existing EndPoint idle timeout.</p>
     *
     * @param idleTimeout the idle timeout in ms to apply to all EndPoints when the network connection limit is reached
     */
    public void setEndPointIdleTimeout(long idleTimeout)
    {
        _endPointIdleTimeout = idleTimeout;
    }

    @ManagedAttribute("The maximum number of network connections")
    public int getMaxNetworkConnectionCount()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _maxNetworkConnections;
        }
    }

    public void setMaxNetworkConnectionCount(int max)
    {
        try (AutoLock ignored = _lock.lock())
        {
            _maxNetworkConnections = max;
        }
    }

    @ManagedAttribute(value = "The number of connected network connections")
    public int getNetworkConnectionCount()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _connections;
        }
    }

    @ManagedAttribute(value = "The number of pending network connections")
    public int getPendingNetworkConnectionCount()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _pendingConnections;
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (_server != null)
                registerConnectors(_server.getConnectors());

            if (LOG.isDebugEnabled())
                LOG.debug("Network connection limit {} for {}", _maxNetworkConnections, _connectors);

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

    private boolean lockedCheck()
    {
        assert _lock.isHeldByCurrentThread();
        int total = _pendingConnections + _connections;
        if (total >= _maxNetworkConnections)
        {
            if (!_limiting)
            {
                _limiting = true;
                LOG.info("Network connection limit {} reached for {}", _maxNetworkConnections, _connectors);
                limit();
            }
            return total > _maxNetworkConnections;
        }
        else
        {
            if (_limiting)
            {
                _limiting = false;
                LOG.info("Network connection limit {} cleared for {}", _maxNetworkConnections, _connectors);
                unlimit();
            }
            return false;
        }
    }

    protected void limit()
    {
        assert _lock.isHeldByCurrentThread();
        for (AbstractConnector c : _connectors)
        {
            c.setAccepting(false);

            if (_endPointIdleTimeout > 0)
            {
                for (EndPoint endPoint : c.getConnectedEndPoints())
                {
                    endPoint.setIdleTimeout(_endPointIdleTimeout);
                }
            }
        }
    }

    protected void unlimit()
    {
        for (AbstractConnector c : _connectors)
        {
            c.setAccepting(true);

            if (_endPointIdleTimeout > 0)
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
            _pendingConnections++;
            if (LOG.isDebugEnabled())
                LOG.debug("Accepting ({}+{}) <= {} {}", _pendingConnections, _connections, _maxNetworkConnections, channel);
            if (lockedCheck())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Closing (limit reached) {}", channel);
                IO.close(channel);
            }
        }
    }

    @Override
    public void onAcceptFailed(SelectableChannel channel, Throwable cause)
    {
        try (AutoLock ignored = _lock.lock())
        {
            _pendingConnections--;
            if (LOG.isDebugEnabled())
                LOG.debug("Accept failed ({}+{}) <= {} {}", _pendingConnections, _connections, _maxNetworkConnections, channel, cause);
            lockedCheck();
        }
    }

    @Override
    public void onAccepted(SelectableChannel channel)
    {
        try (AutoLock ignored = _lock.lock())
        {
            _pendingConnections--;
            _connections++;
            if (LOG.isDebugEnabled())
                LOG.debug("Accepted ({}+{}) <= {} {}", _pendingConnections, _connections, _maxNetworkConnections, channel);
        }
    }

    @Override
    public void onClosed(SelectableChannel channel)
    {
        try (AutoLock ignored = _lock.lock())
        {
            _connections--;
            if (LOG.isDebugEnabled())
                LOG.debug("Closed ({}+{}) <= {} {}", _pendingConnections, _connections, _maxNetworkConnections, channel);
            lockedCheck();
        }
    }
}
