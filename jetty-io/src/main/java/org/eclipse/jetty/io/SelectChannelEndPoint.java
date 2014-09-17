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

    private final Runnable _updateTask = new Runnable()
    {
        @Override
        public void run()
        {
            try
            {
                setKeyInterests();
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
    };
    private final AtomicReference<State> _interestState = new AtomicReference<>(State.SELECTING);
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
        changeInterests(SelectionKey.OP_READ, true);
        return false;
    }

    @Override
    protected void onIncompleteFlush()
    {
        changeInterests(SelectionKey.OP_WRITE, true);
    }

    @Override
    public void onSelected()
    {
        /**
         * This method never runs concurrently with other
         * methods that update _interestState.
         */

        assert _selector.isSelectorThread();

        // Remove the readyOps, that here can only be OP_READ or OP_WRITE (or both).
        int readyOps = _key.readyOps();
        int oldInterestOps = _interestOps;
        int newInterestOps = oldInterestOps & ~readyOps;
        _interestOps = newInterestOps;

        if (!_interestState.compareAndSet(State.SELECTING, State.PENDING))
            throw new IllegalStateException("Invalid state: " + _interestState);

        if (LOG.isDebugEnabled())
            LOG.debug("onSelected {}->{} for {}", oldInterestOps, newInterestOps, this);

        if ((readyOps & SelectionKey.OP_READ) != 0)
            getFillInterest().fillable();
        if ((readyOps & SelectionKey.OP_WRITE) != 0)
            getWriteFlusher().completeWrite();
    }

    @Override
    public void updateKey()
    {
        /**
         * This method may run concurrently with {@link #changeInterests(int, boolean)}.
         */

        assert _selector.isSelectorThread();

        while (true)
        {
            State current = _interestState.get();
            switch (current)
            {
                case SELECTING:
                {
                    // When a whole cycle triggered by changeInterests()
                    // happens, we finish the job by updating the key.
                    setKeyInterests();
                    return;
                }
                case PENDING:
                {
                    if (!_interestState.compareAndSet(current, State.UPDATING))
                        continue;
                    break;
                }
                case UPDATING:
                {
                    // Set the key interest as expected.
                    setKeyInterests();
                    if (!_interestState.compareAndSet(current, State.SELECTING))
                        throw new IllegalStateException();
                    return;
                }
                case CHANGING:
                {
                    // We lost the race to update _interestOps,
                    // let changeInterests() perform the update.
                    return;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
    }

    private void changeInterests(int operation, boolean add)
    {
        /**
         * This method may run concurrently with {@link #updateKey()}.
         */

        boolean pending = false;
        while (true)
        {
            State current = _interestState.get();
            switch (current)
            {
                case SELECTING:
                case PENDING:
                {
                    if (!_interestState.compareAndSet(current, State.CHANGING))
                        continue;
                    pending = current == State.PENDING;
                    break;
                }
                case UPDATING:
                {
                    // We lost the race to update _interestOps, but we
                    // must update it nonetheless, so yield and spin,
                    // waiting for the state to be SELECTING again.
                    Thread.yield();
                    break;
                }
                case CHANGING:
                {
                    int oldInterestOps = _interestOps;
                    int newInterestOps;
                    if (add)
                        newInterestOps = oldInterestOps | operation;
                    else
                        newInterestOps = oldInterestOps & ~operation;

                    if (LOG.isDebugEnabled())
                        LOG.debug("changeInterests pending={} {}->{} for {}", pending, oldInterestOps, newInterestOps, this);

                    if (newInterestOps != oldInterestOps)
                        _interestOps = newInterestOps;

                    if (!_interestState.compareAndSet(current, State.SELECTING))
                        throw new IllegalStateException("Invalid state: " + current);

                    // We only update the key if updateKey() does not do it for us,
                    // because doing it from the selector thread is less expensive.
                    // This must be done after CASing the state above, otherwise the
                    // selector may select and call onSelected() concurrently.
                    submitKeyUpdate(!pending);
                    return;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
    }

    protected void submitKeyUpdate(boolean submit)
    {
        if (submit)
            _selector.updateKey(_updateTask);
    }

    private void setKeyInterests()
    {
        int oldInterestOps = _key.interestOps();
        int newInterestOps = _interestOps;
        if (LOG.isDebugEnabled())
            LOG.debug("Key interests update {} -> {}", oldInterestOps, newInterestOps);
        if (oldInterestOps != newInterestOps)
            _key.interestOps(newInterestOps);
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
                    _interestOps,
                    keyInterests,
                    keyReadiness);
        }
        catch (CancelledKeyException x)
        {
            return String.format("%s{io=%s,kio=-2,kro=-2}", super.toString(), _interestOps);
        }
    }

    private enum State
    {
        SELECTING, PENDING, UPDATING, CHANGING
    }
}
