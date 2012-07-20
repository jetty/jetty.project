// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.io.SelectorManager.ManagedSelector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * An ChannelEndpoint that can be scheduled by {@link SelectorManager}.
 */
public class SelectChannelEndPoint extends ChannelEndPoint implements Runnable, SelectorManager.SelectableAsyncEndPoint
{
    public static final Logger LOG = Log.getLogger(SelectChannelEndPoint.class);

    private final SelectorManager.ManagedSelector _selector;
    private final SelectionKey _key;

    /**
     * The desired value for {@link SelectionKey#interestOps()}
     */
    private volatile int _interestOps;

    /**
     * true if {@link ManagedSelector#destroyEndPoint(AsyncEndPoint)} has not been called
     */
    private final AtomicBoolean _open = new AtomicBoolean();

    private volatile AsyncConnection _connection;

    private final ReadInterest _readInterest = new ReadInterest()
    {
        @Override
        protected boolean needsFill()
        {
            updateKey(SelectionKey.OP_READ, true);
            return false;
        }
    };

    private final WriteFlusher _writeFlusher = new WriteFlusher(this)
    {
        @Override
        protected void onIncompleteFlushed()
        {
            updateKey(SelectionKey.OP_WRITE, true);
        }
    };

    public SelectChannelEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key, long idleTimeout) throws IOException
    {
        super(channel);
        _selector = selectSet;
        _key = key;
        setIdleTimeout(idleTimeout);
    }

    @Override
    public <C> void fillInterested(C context, Callback<C> callback) throws IllegalStateException
    {
        _readInterest.register(context, callback);
    }

    @Override
    public <C> void write(C context, Callback<C> callback, ByteBuffer... buffers) throws IllegalStateException
    {
        _writeFlusher.write(context, callback, buffers);
    }

    @Override
    public AsyncConnection getAsyncConnection()
    {
        return _connection;
    }

    @Override
    public void setAsyncConnection(AsyncConnection connection)
    {
        AsyncConnection old = getAsyncConnection();
        _connection = connection;
        if (old != null && old != connection)
            _selector.getSelectorManager().connectionUpgraded(this, old);
    }

    @Override
    public void onSelected()
    {
        _interestOps = 0;
        if (_key.isReadable())
            _readInterest.readable();
        if (_key.isWritable())
            _writeFlusher.completeWrite();
    }

    @Override
    public void checkTimeout(long now)
    {
        synchronized (this)
        {
            if (isOutputShutdown() || _readInterest.isInterested() || _writeFlusher.isWriting())
            {
                long idleTimestamp = getIdleTimestamp();
                long idleTimeout = getIdleTimeout();

                if (idleTimestamp != 0 && idleTimeout > 0)
                {
                    long idleForMs = now - idleTimestamp;

                    if (idleForMs > idleTimeout)
                    {
                        if (isOutputShutdown())
                            close();
                        notIdle();

                        TimeoutException timeout = new TimeoutException("idle " + idleForMs + "ms");
                        _readInterest.failed(timeout);
                        _writeFlusher.failed(timeout);
                    }
                }
            }
        }
    }

    private void updateKey(int operation, boolean add)
    {
        int oldInterestOps = _interestOps;
        int newInterestOps;
        if (add)
            newInterestOps = oldInterestOps | operation;
        else
            newInterestOps = oldInterestOps & ~operation;

        if (isInputShutdown())
            newInterestOps &= ~SelectionKey.OP_READ;
        if (isOutputShutdown())
            newInterestOps &= ~SelectionKey.OP_WRITE;

        if (newInterestOps != oldInterestOps)
        {
            _interestOps = newInterestOps;
            LOG.debug("Key update {} -> {} for {}", oldInterestOps, newInterestOps, this);
            _selector.submit(this);
        }
        else
        {
            LOG.debug("Ignoring key update {} -> {} for {}", oldInterestOps, newInterestOps, this);
        }
    }

    @Override
    public void run()
    {
        try
        {
            if (getChannel().isOpen())
            {
                int oldInterestOps = _key.interestOps();
                int newInterestOps = _interestOps;
                if (newInterestOps != oldInterestOps)
                    _key.interestOps(newInterestOps);
            }
        }
        catch (CancelledKeyException x)
        {
            LOG.debug("Ignoring key update for concurrently closed channel {}", this);
        }
    }

    @Override
    public void close()
    {
        super.close();
        if (_open.compareAndSet(true, false))
            _selector.destroyEndPoint(this);
    }

    @Override
    public void onOpen()
    {
        _open.compareAndSet(false, true);
    }

    @Override
    public void onClose()
    {
        _writeFlusher.close();
        _readInterest.close();
    }

    @Override
    public String toString()
    {
        // Do NOT use synchronized (this)
        // because it's very easy to deadlock when debugging is enabled.
        // We do a best effort to print the right toString() and that's it.
        String keyString = "";
        if (_key.isValid())
        {
            if (_key.isReadable())
                keyString += "r";
            if (_key.isWritable())
                keyString += "w";
        }
        else
        {
            keyString += "!";
        }
        return String.format("SCEP@%x{l(%s)<->r(%s),open=%b,ishut=%b,oshut=%b,i=%d%s,r=%s,w=%s}-{%s}",
                hashCode(), getRemoteAddress(), getLocalAddress(), isOpen(), isInputShutdown(),
                isOutputShutdown(), _interestOps, keyString, _readInterest, _writeFlusher, getAsyncConnection());
    }
}
