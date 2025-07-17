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
 * <p>A listener that limits the number of {@link EndPoint}s (TCP connections).</p>
 * <p>This listener applies a limit to the number of TCP connections, which when
 * exceeded results in a call to {@link AbstractConnector#setAccepting(boolean)}
 * to prevent further TCP connections to be accepted.</p>
 * <p>This listener can be applied to an entire {@link Server} or to a specific
 * {@link Connector} by adding it via {@link Container#addBean(Object)}.</p>
 * <p>When the number of {@code EndPoint}s is exceeded, the idle timeout of existing
 * {@code EndPoint}s is changed to the value configured in this listener (typically
 * a shorter value).
 * When the number of {@code EndPoint}s returns below the limit, as {@code EndPoint}s
 * are closed, the idle timeout of existing {@code EndPoint}s is restored to that
 * of the connector.</p>
 * <p>Typical usage:</p>
 * <pre>{@code
 * Server server = new Server();
 * server.addBean(new EndPointLimit(5000, server));
 * ...
 * server.start();
 * }</pre>
 *
 * @see LowResourceMonitor
 * @see SelectorManager.AcceptListener
 */
public class EndPointLimit extends AbstractLifeCycle implements SelectorManager.AcceptListener
{
    private static final Logger LOG = LoggerFactory.getLogger(EndPointLimit.class);

    private final AutoLock _lock = new AutoLock();
    private final Server _server;
    private final List<AbstractConnector> _connectors = new ArrayList<>();
    private int _pendingEndPoints;
    private int _endPoints;
    private int _maxEndPoints;
    private long _idleTimeout;
    private boolean _limiting;

    public EndPointLimit(@Name("maxEndPointCount") int maxEndPoints, @Name("server") Server server)
    {
        _maxEndPoints = maxEndPoints;
        _server = server;
    }

    public EndPointLimit(@Name("maxEndPointCount") int maxEndPoints, @Name("connectors") Connector... connectors)
    {
        this(maxEndPoints, (Server)null);
        registerConnectors(connectors);
    }

    private void registerConnectors(Connector[] connectors)
    {
        for (Connector c : connectors)
        {
            if (c instanceof AbstractConnector)
                _connectors.add((AbstractConnector)c);
            else
                LOG.warn("Connector {} is not an instance of {}: endPoints will not be limited", c, AbstractConnector.class.getSimpleName());
        }
    }

    /**
     * @return the idle timeout in ms to apply to all EndPoints when maxEndPoints is reached
     */
    @ManagedAttribute("The EndPoint idle timeout in ms to apply when maxEndPoints is reached")
    public long getIdleTimeout()
    {
        return _idleTimeout;
    }

    /**
     * <p>Sets the idle timeout in ms to apply to all EndPoints when maxEndPoints is reached.</p>
     * <p>A value less than or equal to zero will not change the existing EndPoint idle timeout.</p>
     *
     * @param idleTimeout the idle timeout in ms to apply to all EndPoints when maxEndPoints is reached
     */
    public void setIdleTimeout(long idleTimeout)
    {
        _idleTimeout = idleTimeout;
    }

    @ManagedAttribute("The maximum number of EndPoints")
    public int getMaxEndPointCount()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _maxEndPoints;
        }
    }

    public void setMaxEndPointCount(int max)
    {
        try (AutoLock ignored = _lock.lock())
        {
            _maxEndPoints = max;
        }
    }

    @ManagedAttribute(value = "The number of connected EndPoints")
    public int getEndPointCount()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _endPoints;
        }
    }

    @ManagedAttribute(value = "The number of pending EndPoints")
    public int getPendingEndPointCount()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _pendingEndPoints;
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
                LOG.debug("EndPoints limit {} for {}", _maxEndPoints, _connectors);

            _endPoints = 0;
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
            _endPoints = 0;
            if (_server != null)
                _connectors.clear();
        }
    }

    private boolean lockedCheck()
    {
        assert _lock.isHeldByCurrentThread();
        int total = _pendingEndPoints + _endPoints;
        if (total >= _maxEndPoints)
        {
            if (!_limiting)
            {
                _limiting = true;
                LOG.info("EndPoint limit {} reached for {}", _maxEndPoints, _connectors);
                limit();
            }
            return total > _maxEndPoints;
        }
        else
        {
            if (_limiting)
            {
                _limiting = false;
                LOG.info("EndPoint limit {} cleared for {}", _maxEndPoints, _connectors);
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
            _pendingEndPoints++;
            if (LOG.isDebugEnabled())
                LOG.debug("Accepting ({}+{}) <= {} {}", _pendingEndPoints, _endPoints, _maxEndPoints, channel);
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
            _pendingEndPoints--;
            if (LOG.isDebugEnabled())
                LOG.debug("Accept failed ({}+{}) <= {} {}", _pendingEndPoints, _endPoints, _maxEndPoints, channel, cause);
            lockedCheck();
        }
    }

    @Override
    public void onAccepted(SelectableChannel channel)
    {
        try (AutoLock ignored = _lock.lock())
        {
            _pendingEndPoints--;
            _endPoints++;
            if (LOG.isDebugEnabled())
                LOG.debug("Accepted ({}+{}) <= {} {}", _pendingEndPoints, _endPoints, _maxEndPoints, channel);
        }
    }

    @Override
    public void onClosed(SelectableChannel channel)
    {
        try (AutoLock ignored = _lock.lock())
        {
            _endPoints--;
            if (LOG.isDebugEnabled())
                LOG.debug("Closed ({}+{}) <= {} {}", _pendingEndPoints, _endPoints, _maxEndPoints, channel);
            lockedCheck();
        }
    }
}
