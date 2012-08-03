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
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.SelectorManager.ManagedSelector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * An ChannelEndpoint that can be scheduled by {@link SelectorManager}.
 */
public class SelectChannelEndPoint extends ChannelEndPoint implements SelectorManager.SelectableEndPoint
{
    public static final Logger LOG = Log.getLogger(SelectChannelEndPoint.class);

    private final AtomicReference<Future<?>> _timeout = new AtomicReference<>();
    private final Runnable _idleTask = new Runnable()
    {
        @Override
        public void run()
        {
            checkIdleTimeout();
        }
    };
    

    private final Runnable _updateTask = new Runnable()
    {
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
                        setKeyInterests(oldInterestOps, newInterestOps);
                }
            }
            catch (CancelledKeyException x)
            {
                LOG.debug("Ignoring key update for concurrently closed channel {}", this);
                close();
            }
            catch (Exception x)
            {
                LOG.warn("Ignoring key update for " + this, x);
                close();
            }
        }
    };
    
    /**
     * true if {@link ManagedSelector#destroyEndPoint(EndPoint)} has not been called
     */
    private final AtomicBoolean _open = new AtomicBoolean();
    private final ReadInterest _readInterest = new ReadInterest()
    {
        @Override
        protected boolean needsFill()
        {
            return SelectChannelEndPoint.this.needsFill();
        }
    };
    private final WriteFlusher _writeFlusher = new WriteFlusher(this)
    {
        @Override
        protected void onIncompleteFlushed()
        {
            SelectChannelEndPoint.this.onIncompleteFlush();
        }
    };
    private final SelectorManager.ManagedSelector _selector;
    private final SelectionKey _key;
    private final ScheduledExecutorService _scheduler;
    /**
     * The desired value for {@link SelectionKey#interestOps()}
     */
    private volatile int _interestOps;

    public SelectChannelEndPoint(SocketChannel channel, ManagedSelector selector, SelectionKey key, ScheduledExecutorService scheduler, long idleTimeout) throws IOException
    {
        super(channel);
        _selector = selector;
        _key = key;
        _scheduler = scheduler;
        setIdleTimeout(idleTimeout);
    }

    @Override
    public void setIdleTimeout(long idleTimeout)
    {
        super.setIdleTimeout(idleTimeout);
        scheduleIdleTimeout(idleTimeout);
    }

    protected boolean needsFill()
    {
        updateLocalInterests(SelectionKey.OP_READ, true);
        return false;
    }

    protected void onIncompleteFlush()
    {
        updateLocalInterests(SelectionKey.OP_WRITE, true);
    }

    private void scheduleIdleTimeout(long delay)
    {
        Future<?> newTimeout = null;
        if (isOpen() && delay > 0)
        {
            LOG.debug("{} scheduling idle timeout in {} ms", this, delay);
            newTimeout = _scheduler.schedule(_idleTask, delay, TimeUnit.MILLISECONDS);
        }
        else
        {
            LOG.debug("{} skipped scheduling idle timeout ({} ms)", this, delay);
        }
        Future<?> oldTimeout = _timeout.getAndSet(newTimeout);
        if (oldTimeout != null)
            oldTimeout.cancel(false);
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
    public void setConnection(Connection connection)
    {
        // TODO should this be on AbstractEndPoint?
        Connection old = getConnection();
        super.setConnection(connection);
        if (old != null && old != connection)
            _selector.getSelectorManager().connectionUpgraded(this, old);
    }

    @Override
    public void onSelected()
    {
        int oldInterestOps = _key.interestOps();
        int readyOps = _key.readyOps();
        int newInterestOps = oldInterestOps & ~readyOps;
        setKeyInterests(oldInterestOps, newInterestOps);
        updateLocalInterests(readyOps, false);
        if (_key.isReadable())
            _readInterest.readable();
        if (_key.isWritable())
            _writeFlusher.completeWrite();
    }

    private void checkIdleTimeout()
    {
        if (isOpen())
        {
            long idleTimestamp = getIdleTimestamp();
            long idleTimeout = getIdleTimeout();
            long idleElapsed = System.currentTimeMillis() - idleTimestamp;
            long idleLeft = idleTimeout - idleElapsed;

            LOG.debug("{} idle timeout check, elapsed: {} ms, remaining: {} ms", this, idleElapsed, idleLeft);

            if (isOutputShutdown() || _readInterest.isInterested() || _writeFlusher.isInProgress())
            {
                if (idleTimestamp != 0 && idleTimeout > 0)
                {
                    if (idleLeft <= 0)
                    {
                        LOG.debug("{} idle timeout expired", this);

                        TimeoutException timeout = new TimeoutException("Idle timeout expired: " + idleElapsed + "/" + idleTimeout + " ms");
                        _readInterest.failed(timeout);
                        _writeFlusher.onFail(timeout);

                        if (isOutputShutdown())
                            close();
                        notIdle();
                    }
                }
            }
            scheduleIdleTimeout(idleLeft > 0 ? idleLeft : idleTimeout);
        }
    }

    private void updateLocalInterests(int operation, boolean add)
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
            LOG.debug("Local interests updated {} -> {} for {}", oldInterestOps, newInterestOps, this);
            _selector.submit(_updateTask);
        }
        else
        {
            LOG.debug("Ignoring local interests update {} -> {} for {}", oldInterestOps, newInterestOps, this);
        }
    }


    private void setKeyInterests(int oldInterestOps, int newInterestOps)
    {
        LOG.debug("Key interests updated {} -> {}", oldInterestOps, newInterestOps);
        _key.interestOps(newInterestOps);
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
        super.onOpen();
        _open.compareAndSet(false, true);
    }

    @Override
    public void onClose()
    {
        super.onClose();
        _writeFlusher.onClose();
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
                isOutputShutdown(), _interestOps, keyString, _readInterest, _writeFlusher, getConnection());
    }
}
