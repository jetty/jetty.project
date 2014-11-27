//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.io;

import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.SelectorManager.ManagedSelector;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * An ChannelEndpoint that can be scheduled by {@link SelectorManager}.
 */
public class SelectChannelEndPoint extends ChannelEndPoint implements SelectorManager.SelectableEndPoint
{
    public static final Logger LOG = Log.getLogger(SelectChannelEndPoint.class);

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
                    int newInterestOps = _interestOps.get();
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
    private final SelectorManager.ManagedSelector _selector;
    private final SelectionKey _key;
    /**
     * The desired value for {@link SelectionKey#interestOps()}
     */
    private final AtomicInteger _interestOps = new AtomicInteger();

    public SelectChannelEndPoint(SocketChannel channel, ManagedSelector selector, SelectionKey key, Scheduler scheduler, long idleTimeout)
    {
        super(scheduler,channel);
        _selector = selector;
        _key = key;
        setIdleTimeout(idleTimeout);
    }

    @Override
    protected boolean needsFill()
    {
        updateLocalInterests(SelectionKey.OP_READ, true);
        return false;
    }

    @Override
    protected void onIncompleteFlush()
    {
        updateLocalInterests(SelectionKey.OP_WRITE, true);
    }

    @Override
    public void onSelected()
    {
        assert _selector.isSelectorThread();
        int oldInterestOps = _key.interestOps();
        int readyOps = _key.readyOps();
        int newInterestOps = oldInterestOps & ~readyOps;
        setKeyInterests(oldInterestOps, newInterestOps);
        updateLocalInterests(readyOps, false);
        if (_key.isReadable())
            getFillInterest().fillable();
        if (_key.isWritable())
            getWriteFlusher().completeWrite();
    }


    private void updateLocalInterests(int operation, boolean add)
    {
        while (true)
        {
            int oldInterestOps = _interestOps.get();
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
                if (_interestOps.compareAndSet(oldInterestOps, newInterestOps))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Local interests updating {} -> {} for {}", oldInterestOps, newInterestOps, this);
                    _selector.updateKey(_updateTask);
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Local interests update conflict: now {}, was {}, attempted {} for {}", _interestOps.get(), oldInterestOps, newInterestOps, this);
                    continue;
                }
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Ignoring local interests update {} -> {} for {}", oldInterestOps, newInterestOps, this);
            }
            break;
        }
    }


    private void setKeyInterests(int oldInterestOps, int newInterestOps)
    {
        _key.interestOps(newInterestOps);
        if (LOG.isDebugEnabled())
            LOG.debug("Key interests updated {} -> {} on {}", oldInterestOps, newInterestOps, this);
    }

    @Override
    public void close()
    {
        if (_open.compareAndSet(true, false))
        {
            super.close();
            _selector.destroyEndPoint(this);
        }
    }

    @Override
    public boolean isOpen()
    {
        // We cannot rely on super.isOpen(), because there is a race between calls to close() and isOpen():
        // a thread may call close(), which flips the boolean but has not yet called super.close(), and
        // another thread calls isOpen() which would return true - wrong - if based on super.isOpen().
        return _open.get();
    }

    @Override
    public void onOpen()
    {
        if (_open.compareAndSet(false, true))
            super.onOpen();
    }

    @Override
    public String toString()
    {
        // Do NOT use synchronized (this)
        // because it's very easy to deadlock when debugging is enabled.
        // We do a best effort to print the right toString() and that's it.
        try
        {
            boolean valid = _key!=null && _key.isValid();
            int keyInterests = valid ? _key.interestOps() : -1;
            int keyReadiness = valid ? _key.readyOps() : -1;
            return String.format("%s{io=%d,kio=%d,kro=%d}",
                    super.toString(),
                    _interestOps.get(),
                    keyInterests,
                    keyReadiness);
        }
        catch (CancelledKeyException x)
        {
            return String.format("%s{io=%s,kio=-2,kro=-2}", super.toString(), _interestOps.get());
        }
    }
}
