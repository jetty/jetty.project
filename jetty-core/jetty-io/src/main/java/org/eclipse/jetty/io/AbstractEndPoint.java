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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Partial implementation of EndPoint that uses {@link FillInterest} and {@link WriteFlusher}.</p>
 */
public abstract class AbstractEndPoint extends IdleTimeout implements EndPoint
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractEndPoint.class);

    private final AtomicReference<State> _state = new AtomicReference<>(State.OPEN);
    private final long _created = System.currentTimeMillis();
    private volatile Connection _connection;
    private final FillInterest _fillInterest = new FillInterest()
    {
        @Override
        protected void needsFillInterest() throws IOException
        {
            AbstractEndPoint.this.needsFillInterest();
        }
    };
    private final WriteFlusher _writeFlusher = new WriteFlusher(this)
    {
        @Override
        protected void onIncompleteFlush()
        {
            AbstractEndPoint.this.onIncompleteFlush();
        }
    };

    protected AbstractEndPoint(Scheduler scheduler)
    {
        super(scheduler);
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        SocketAddress local = getLocalSocketAddress();
        if (local instanceof InetSocketAddress)
            return (InetSocketAddress)local;
        return null;
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return null;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        SocketAddress remote = getRemoteSocketAddress();
        if (remote instanceof InetSocketAddress)
            return (InetSocketAddress)remote;
        return null;
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return null;
    }

    protected final void shutdownInput()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("shutdownInput {}", this);
        while (true)
        {
            State s = _state.get();
            switch (s)
            {
                case OPEN:
                    if (!_state.compareAndSet(s, State.ISHUTTING))
                        continue;
                    try
                    {
                        doShutdownInput();
                    }
                    finally
                    {
                        if (!_state.compareAndSet(State.ISHUTTING, State.ISHUT))
                        {
                            // If somebody else switched to CLOSED while we were ishutting,
                            // then we do the close for them
                            if (_state.get() == State.CLOSED)
                                doOnClose(null);
                        }
                    }
                    return;

                case ISHUTTING:  // Somebody else ishutting
                case ISHUT: // Already ishut
                    return;

                case OSHUTTING:
                    if (!_state.compareAndSet(s, State.CLOSED))
                        continue;
                    // The thread doing the OSHUT will close
                    return;

                case OSHUT:
                    if (!_state.compareAndSet(s, State.CLOSED))
                        continue;
                    // Already OSHUT so we close
                    doOnClose(null);
                    return;

                case CLOSED: // already closed
                    return;

                default:
                    throw new IllegalStateException(s.toString());
            }
        }
    }

    @Override
    public final void shutdownOutput()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("shutdownOutput {}", this);
        while (true)
        {
            State s = _state.get();
            switch (s)
            {
                case OPEN:
                    if (!_state.compareAndSet(s, State.OSHUTTING))
                        continue;
                    try
                    {
                        doShutdownOutput();
                    }
                    finally
                    {
                        if (!_state.compareAndSet(State.OSHUTTING, State.OSHUT))
                        {
                            // If somebody else switched to CLOSED while we were oshutting,
                            // then we do the close for them
                            if (_state.get() == State.CLOSED)
                                doOnClose(null);
                        }
                    }
                    return;

                case ISHUTTING:
                    if (!_state.compareAndSet(s, State.CLOSED))
                        continue;
                    // The thread doing the ISHUT will close
                    return;

                case ISHUT:
                    if (!_state.compareAndSet(s, State.CLOSED))
                        continue;
                    // Already ISHUT so we close
                    doOnClose(null);
                    return;

                case OSHUTTING:  // Somebody else oshutting
                case OSHUT: // Already oshut
                    return;

                case CLOSED: // already closed
                    return;

                default:
                    throw new IllegalStateException(s.toString());
            }
        }
    }

    @Override
    public final void close()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("close {}", this);
        close(null);
    }

    public final void close(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("close({}) {}", failure, this);
        while (true)
        {
            State s = _state.get();
            switch (s)
            {
                case OPEN:
                case ISHUT: // Already ishut
                case OSHUT: // Already oshut
                    if (!_state.compareAndSet(s, State.CLOSED))
                        continue;
                    doOnClose(failure);
                    return;

                case ISHUTTING: // Somebody else ishutting
                case OSHUTTING: // Somebody else oshutting
                    if (!_state.compareAndSet(s, State.CLOSED))
                        continue;
                    // The thread doing the IO SHUT will call doOnClose
                    return;

                case CLOSED: // already closed
                    return;

                default:
                    throw new IllegalStateException(s.toString());
            }
        }
    }

    protected void doShutdownInput()
    {
    }

    protected void doShutdownOutput()
    {
    }

    private void doOnClose(Throwable failure)
    {
        try
        {
            doClose();
        }
        finally
        {
            if (failure == null)
                onClose();
            else
                onClose(failure);
        }
    }

    protected void doClose()
    {
    }

    @Override
    public boolean isOutputShutdown()
    {
        return switch (_state.get())
        {
            case CLOSED, OSHUT, OSHUTTING -> true;
            default -> false;
        };
    }

    @Override
    public boolean isInputShutdown()
    {
        return switch (_state.get())
        {
            case CLOSED, ISHUT, ISHUTTING -> true;
            default -> false;
        };
    }

    @Override
    public boolean isOpen()
    {
        return _state.get() != State.CLOSED;
    }

    @Override
    public long getCreatedTimeStamp()
    {
        return _created;
    }

    @Override
    public Connection getConnection()
    {
        return _connection;
    }

    @Override
    public void setConnection(Connection connection)
    {
        _connection = connection;
    }

    protected void reset()
    {
        _state.set(State.OPEN);
        _writeFlusher.onClose();
        _fillInterest.onClose();
    }

    @Override
    public void onOpen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onOpen {}", this);
        if (_state.get() != State.OPEN)
            throw new IllegalStateException();
    }

    @Override
    public final void onClose()
    {
        onClose(null);
    }

    @Override
    public void onClose(Throwable failure)
    {
        super.onClose();
        if (failure == null)
        {
            _writeFlusher.onClose();
            _fillInterest.onClose();
        }
        else
        {
            _writeFlusher.onFail(failure);
            _fillInterest.onFail(failure);
        }
    }

    @Override
    public void fillInterested(Callback callback)
    {
        notIdle();
        _fillInterest.register(callback);
    }

    @Override
    public boolean tryFillInterested(Callback callback)
    {
        notIdle();
        return _fillInterest.tryRegister(callback);
    }

    @Override
    public boolean isFillInterested()
    {
        return _fillInterest.isInterested();
    }

    @Override
    public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
    {
        _writeFlusher.write(callback, buffers);
    }

    protected abstract void onIncompleteFlush();

    protected abstract void needsFillInterest() throws IOException;

    public FillInterest getFillInterest()
    {
        return _fillInterest;
    }

    public WriteFlusher getWriteFlusher()
    {
        return _writeFlusher;
    }

    @Override
    protected void onIdleExpired(TimeoutException timeout)
    {
        Connection connection = _connection;
        if (connection != null && !connection.onIdleExpired())
            return;

        boolean outputShutdown = isOutputShutdown();
        boolean inputShutdown = isInputShutdown();
        boolean fillFailed = _fillInterest.onFail(timeout);
        boolean writeFailed = _writeFlusher.onFail(timeout);

        // If the endpoint is half closed and there was no fill/write handling, then close here.
        // This handles the situation where the connection has completed its close handling
        // and the endpoint is half closed, but the other party does not complete the close.
        // This perhaps should not check for half closed, however the servlet spec case allows
        // for a dispatched servlet or suspended request to extend beyond the connections idle
        // time.  So if this test would always close an idle endpoint that is not handled, then
        // we would need a mode to ignore timeouts for some HTTP states
        if (isOpen() && (inputShutdown || outputShutdown) && !(fillFailed || writeFailed))
            close();
        else
            LOG.debug("handled idle inputShutdown={} outputShutdown={} fillFailed={} writeFailed={} for {}",
                inputShutdown,
                outputShutdown,
                fillFailed,
                writeFailed,
                this);
    }

    @Override
    public void upgrade(Connection newConnection)
    {
        Connection oldConnection = getConnection();

        ByteBuffer buffer = (oldConnection instanceof Connection.UpgradeFrom)
            ? ((Connection.UpgradeFrom)oldConnection).onUpgradeFrom()
            : null;
        oldConnection.onClose(null);
        oldConnection.getEndPoint().setConnection(newConnection);

        if (LOG.isDebugEnabled())
            LOG.debug("{} upgrading from {} to {} with {}",
                this, oldConnection, newConnection, BufferUtil.toDetailString(buffer));

        if (BufferUtil.hasContent(buffer))
        {
            if (newConnection instanceof Connection.UpgradeTo)
                ((Connection.UpgradeTo)newConnection).onUpgradeTo(buffer);
            else
                throw new IllegalStateException("Cannot upgrade: " + newConnection + " does not implement " + Connection.UpgradeTo.class.getName());
        }

        newConnection.onOpen();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[%s]->[%s]", getClass().getSimpleName(), hashCode(), toEndPointString(), toConnectionString());
    }

    public String toEndPointString()
    {
        return String.format("{l=%s,r=%s,%s,fill=%s,flush=%s,to=%d/%d}",
            getLocalSocketAddress(),
            getRemoteSocketAddress(),
            _state.get(),
            _fillInterest.toStateString(),
            _writeFlusher.toStateString(),
            getIdleFor(),
            getIdleTimeout());
    }

    public String toConnectionString()
    {
        Connection connection = getConnection();
        if (connection == null) // can happen during upgrade
            return "<null>";
        if (connection instanceof AbstractConnection)
            return ((AbstractConnection)connection).toConnectionString();
        return String.format("%s@%x", connection.getClass().getSimpleName(), connection.hashCode());
    }

    private enum State
    {
        OPEN, ISHUTTING, ISHUT, OSHUTTING, OSHUT, CLOSED
    }
}
