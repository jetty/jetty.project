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
import java.util.concurrent.atomic.AtomicReference;

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

    private enum State
    {
        UPDATED, UPDATE_PENDING, LOCKED
    }
    
    private final AtomicReference<State> _interestState = new AtomicReference<>(State.UPDATED);
    /**
     * true if {@link ManagedSelector#destroyEndPoint(EndPoint)} has not been called
     */
    private final AtomicBoolean _open = new AtomicBoolean();
    private final SelectorManager.ManagedSelector _selector;
    private final SelectionKey _key;
    /**
     * The desired value for {@link SelectionKey#interestOps()}
     */
    private int _interestOps;
    
    private final Runnable _runUpdateKey = new Runnable() { public void run() { updateKey(); } };

    public SelectChannelEndPoint(SocketChannel channel, ManagedSelector selector, SelectionKey key, Scheduler scheduler, long idleTimeout)
    {
        super(scheduler, channel);
        _selector = selector;
        _key = key;
        setIdleTimeout(idleTimeout);
    }

    @Override
    protected boolean needsFill()
    {
        changeInterests(SelectionKey.OP_READ);
        return false;
    }

    @Override
    protected void onIncompleteFlush()
    {
        changeInterests(SelectionKey.OP_WRITE);
    }

    @Override
    public void onSelected()
    {
        /**
         * This method may run concurrently with {@link #changeInterests(int)}.
         */

        assert _selector.isSelectorThread();

        while (true)
        {
            State current = _interestState.get();
            if (LOG.isDebugEnabled())
                LOG.debug("Processing, state {} for {}", current, this);
            switch (current)
            {
                case UPDATE_PENDING:
                case UPDATED:
                {
                    if (!_interestState.compareAndSet(current, State.LOCKED))
                        continue;

                    int readyOps;
                    try
                    {
                        // Remove the readyOps, that here can only be OP_READ or OP_WRITE (or both).
                        readyOps = _key.readyOps();
                        int oldInterestOps = _interestOps;
                        int newInterestOps = oldInterestOps & ~readyOps;
                        _interestOps = newInterestOps;

                        if (LOG.isDebugEnabled())
                            LOG.debug("onSelected {}->{} for {}", oldInterestOps, newInterestOps, this);
                    }
                    finally
                    {
                        // We have been called by onSelected, so we know the
                        // selector will call updateKey before selecting again.
                        _interestState.set(State.UPDATE_PENDING);
                    }
                    
                    if ((readyOps & SelectionKey.OP_READ) != 0)
                        getFillInterest().fillable();
                    if ((readyOps & SelectionKey.OP_WRITE) != 0)
                        getWriteFlusher().completeWrite();

                    return;
                }
                case LOCKED:
                {
                    // Wait for other operations to finish.
                    Thread.yield();
                    break;
                }
                default:
                {
                    throw new IllegalStateException("Invalid state: " + current);
                }
            }
        }
    }

    @Override
    public void updateKey()
    {
        /**
         * This method may run concurrently with
         * {@link #changeInterests(int)} and {@link #onSelected()}.
         */

        while (true)
        {
            State current = _interestState.get();
            if (LOG.isDebugEnabled())
                LOG.debug("Updating key, state {} for {}", current, this);
            switch (current)
            {
                case UPDATE_PENDING:
                case UPDATED:
                {
                    if (!_interestState.compareAndSet(current, State.LOCKED))
                        continue;

                    try
                    {
                        // Set the key interest as expected.
                        setKeyInterests();
                        return;
                    }
                    finally
                    {
                        // We have just updated the key, so we are now updated!
                        // and no call to unpdateKey is pending
                        _interestState.set(State.UPDATED);
                    }
                }
                case LOCKED:
                {
                    // Wait for other operations to finish.
                    Thread.yield();
                    break;
                }
                default:
                {
                    throw new IllegalStateException("Invalid state: " + current);
                }
            }
        }
    }

    private void changeInterests(int operation)
    {
        /**
         * This method may run concurrently with
         * {@link #updateKey()} and {@link #onSelected()}.
         */

        while (true)
        {
            State current = _interestState.get();
            if (LOG.isDebugEnabled())
                LOG.debug("Changing interests in state {} for {}", current, this);
            switch (current)
            {
                case UPDATE_PENDING:
                case UPDATED:
                {
                    if (!_interestState.compareAndSet(current, State.LOCKED))
                        continue;

                    try
                    {
                        int oldInterestOps = _interestOps;
                        int newInterestOps = oldInterestOps | operation;

                        if (LOG.isDebugEnabled())
                            LOG.debug("changeInterests s={} {}->{} for {}", current, oldInterestOps, newInterestOps, this);

                        if (newInterestOps != oldInterestOps)
                            _interestOps = newInterestOps;

                        if (current==State.UPDATED)
                            _selector.submit(_runUpdateKey);
                    }
                    finally
                    {
                        // If we were pending a call to updateKey, then we still are.
                        // If we were not, then we have submitted a callback to runUpdateKey, so we now are pending.
                        _interestState.set(State.UPDATE_PENDING);
                    }
                    
                    return;
                }
                case LOCKED:
                {
                    // We lost the race to update _interestOps, but we
                    // must update it nonetheless, so yield and spin,
                    // waiting for our chance to update _interestOps.
                    Thread.yield();
                    break;
                }
                default:
                {
                    throw new IllegalStateException("Invalid state: " + current);
                }
            }
        }
    }

    private void setKeyInterests()
    {
        try
        {
            int oldInterestOps = _key.interestOps();
            int newInterestOps = _interestOps;
            if (oldInterestOps != newInterestOps)
                _key.interestOps(newInterestOps);
            if (LOG.isDebugEnabled())
                LOG.debug("Key interests updated {} -> {} on {}", oldInterestOps, newInterestOps, this);
        }
        catch (CancelledKeyException x)
        {
            LOG.debug("Ignoring key update for concurrently closed channel {}", this);
            close();
        }
        catch (Throwable x)
        {
            LOG.warn("Ignoring key update for " + this, x);
            close();
        }
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
            boolean valid = _key != null && _key.isValid();
            int keyInterests = valid ? _key.interestOps() : -1;
            int keyReadiness = valid ? _key.readyOps() : -1;
            return String.format("%s{io=%d,kio=%d,kro=%d}",
                    super.toString(),
                    _interestOps,
                    keyInterests,
                    keyReadiness);
        }
        catch (CancelledKeyException x)
        {
            return String.format("%s{io=%s,kio=-2,kro=-2}", super.toString(), _interestOps);
        }
    }

}
