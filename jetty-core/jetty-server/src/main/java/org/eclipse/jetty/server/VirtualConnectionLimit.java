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

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * <p>A Listener that limits the number of virtual {@link Connection}s.</p>
 * <p>This listener applies a limit to the number of connections, which when
 * exceeded results in a call to {@link AbstractConnector#setAccepting(boolean)}
 * to prevent further connections being received, and any further virtual connections
 * that are opened will be immediately closed.
 * This listener can be applied to an entire {@link Server} or to a specific
 * {@link Connector} by adding it via {@link Container#addBean(Object)}.
 * </p>
 * <p>When the number of connections is exceeded, the idle timeout of existing
 * connections is changed with the value configured in this listener (typically
 * a shorter value).</p>
 * <p>This differs from {@link NetworkConnectionLimit} in that it limits the number of
 * virtual connections not network connections. One network connection may have multiple
 * virtual connections associated with it. For example over an HTTP/2 connection multiple
 * streams can be upgraded to WebSocket, each counting as their own virtual connection.</p>
 * <p>
 * <b>Typical usage:</b>
 * </p>
 * <pre>{@code
 *   Server server = new Server();
 *   server.addBean(new VirtualConnectionLimit(5000,server));
 *   ...
 *   server.start();
 * }</pre>
 *
 *
 * @see LowResourceMonitor
 * @see Connection.Listener
 * @see SelectorManager.AcceptListener
 */
@ManagedObject
public class VirtualConnectionLimit extends AbstractLifeCycle implements Listener, SelectorManager.AcceptListener
{
    private static final Logger LOG = LoggerFactory.getLogger(VirtualConnectionLimit.class);

    private final AutoLock _lock = new AutoLock();
    private final Server _server;
    private final List<AbstractConnector> _connectors = new ArrayList<>();
    private final Set<SelectableChannel> _acceptedChannels = new HashSet<>();
    private int _pendingConnections;
    private int _connections;
    private int _maxVirtualConnections;
    private long _endPointIdleTimeout;
    private boolean _limiting = false;

    public VirtualConnectionLimit(@Name("maxConnections") int maxVirtualConnections, @Name("server") Server server)
    {
        _maxVirtualConnections = maxVirtualConnections;
        _server = server;
    }

    public VirtualConnectionLimit(@Name("maxConnections") int maxVirtualConnections, @Name("connectors") Connector... connectors)
    {
        this(maxVirtualConnections, (Server)null);
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

    @ManagedAttribute("The maximum number of virtual connections")
    public int getMaxVirtualConnectionCount()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _maxVirtualConnections;
        }
    }

    public void setMaxVirtualConnectionCount(int max)
    {
        try (AutoLock ignored = _lock.lock())
        {
            _maxVirtualConnections = max;
        }
    }

    @ManagedAttribute(value = "The number of connected virtual connections")
    public int getVirtualConnectionCount()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _connections;
        }
    }

    @ManagedAttribute(value = "The number of pending virtual connections")
    public int getPendingVirtualConnectionCount()
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
                LOG.debug("Connection limit {} for {}", _maxVirtualConnections, _connectors);

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
        if (total >= _maxVirtualConnections)
        {
            if (!_limiting)
            {
                _limiting = true;
                LOG.info("Virtual connection limit {} reached for {}", _maxVirtualConnections, _connectors);
                limit();
            }
            return total > _maxVirtualConnections;
        }
        else
        {
            if (_limiting)
            {
                _limiting = false;
                LOG.info("Virtual connection limit {} cleared for {}", _maxVirtualConnections, _connectors);
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
                LOG.debug("Accepting ({}+{}) <= {} {}", _pendingConnections, _connections, _maxVirtualConnections, channel);

            for (AbstractConnector c : _connectors)
            {
                System.err.println("    " + c + ": " + c.isAccepting());
            }

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
                LOG.debug("Accept failed ({}+{}) <= {} {}", _pendingConnections, _connections, _maxVirtualConnections, channel, cause);
            lockedCheck();
        }
    }

    @Override
    public void onAccepted(SelectableChannel channel)
    {
        try (AutoLock ignored = _lock.lock())
        {
            _acceptedChannels.add(channel);
        }
    }

    @Override
    public void onOpened(Connection connection)
    {
        try (AutoLock ignored = _lock.lock())
        {
            // We should only decrement _accepting once per SelectableChannel.
            Object transport = connection.getEndPoint().getTransport();
            if (transport instanceof SelectableChannel selectableChannel && _acceptedChannels.remove(selectableChannel))
                _pendingConnections--;

            _connections++;
            if (LOG.isDebugEnabled())
                LOG.debug("Opened ({}+{}) <= {} {}", _pendingConnections, _connections, _maxVirtualConnections, connection);

            // HTTP/2 will need to rely on this close to prevent streams on an existing HTTP2Connection from exceeding
            // the limit by upgrading streams to WebSocket, as the call to connector.setAccepting(false) will not prevent this.
            if (lockedCheck())
                connection.getEndPoint().close(new IOException("Exceeded Connection Limit"));
        }
    }

    @Override
    public void onClosed(Connection connection)
    {
        try (AutoLock ignored = _lock.lock())
        {
            _connections--;
            if (LOG.isDebugEnabled())
                LOG.debug("Closed ({}+{}) <= {} {}", _pendingConnections, _connections, _maxVirtualConnections, connection);
            lockedCheck();
        }
    }
}
