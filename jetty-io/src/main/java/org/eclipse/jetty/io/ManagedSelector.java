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

import java.io.Closeable;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.SelectorManager.SelectableEndPoint;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * <p>{@link ManagedSelector} wraps a {@link Selector} simplifying non-blocking operations on channels.</p>
 * <p>{@link ManagedSelector} runs the select loop, which waits on {@link Selector#select()} until events
 * happen for registered channels. When events happen, it notifies the {@link EndPoint} associated
 * with the channel.</p>
 */
public class ManagedSelector extends AbstractLifeCycle implements Runnable, Dumpable, ExecutionStrategy.Producer
{
    protected static final Logger LOG = Log.getLogger(ManagedSelector.class);
    private final ExecutionStrategy _strategy;
    private final SelectorManager _selectorManager;
    private final AtomicReference<State> _state = new AtomicReference<>(State.PROCESSING);
    private final int _id;
    private List<Runnable> _runChanges = new ArrayList<>();
    private List<Runnable> _addChanges = new ArrayList<>();
    private Iterator<SelectionKey> _selections = Collections.emptyIterator();
    private Set<SelectionKey> _selectedKeys = Collections.emptySet();
    private Selector _selector;

    public ManagedSelector(SelectorManager selectorManager, int id)
    {
        _selectorManager = selectorManager;
        _strategy = ExecutionStrategy.Factory.instanceFor(this, selectorManager.getExecutor());
        _id = id;
        setStopTimeout(5000);
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        _selector = newSelector();
        _state.set(State.PROCESSING);
    }

    protected Selector newSelector() throws IOException
    {
        return Selector.open();
    }

    public int size()
    {
        Selector s = _selector;
        if (s==null)
            return 0;
        return s.keys().size();
    }

    @Override
    protected void doStop() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Stopping {}", this);
        Stop stop = new Stop();
        submit(stop);
        stop.await(getStopTimeout());
        if (LOG.isDebugEnabled())
            LOG.debug("Stopped {}", this);
    }


    /**
     * <p>Submits a change to be executed in the selector thread.</p>
     * <p>Changes may be submitted from any thread, and the selector thread woken up
     * (if necessary) to execute the change.</p>
     *
     * @param change the change to submit
     */
    public void submit(Runnable change)
    {
        // This method may be called from the selector thread, and therefore
        // we could directly run the change without queueing, but this may
        // lead to stack overflows on a busy server, so we always offer the
        // change to the queue and process the state.

        if (LOG.isDebugEnabled())
            LOG.debug("Queued change {}", change);

        out:
        while (true)
        {
            State state = _state.get();
            switch (state)
            {
                case PROCESSING:
                    // If we are processing
                    if (!_state.compareAndSet(State.PROCESSING, State.LOCKED))
                        continue;
                    // we can just lock and add the change
                    _addChanges.add(change);
                    _state.set(State.PROCESSING);
                    break out;

                case SELECTING:
                    // If we are processing
                    if (!_state.compareAndSet(State.SELECTING, State.LOCKED))
                        continue;
                    // we must lock, add the change and wakeup the selector
                    _addChanges.add(change);
                    _selector.wakeup();
                    // we move to processing state now, because the selector will
                    // not block and this avoids extra calls to wakeup()
                    _state.set(State.PROCESSING);
                    break out;

                case LOCKED:
                    Thread.yield();
                    continue;

                default:
                    throw new IllegalStateException();
            }
        }
    }

    protected void runChange(Runnable change)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Running change {}", change);
            change.run();
        }
        catch (Throwable x)
        {
            LOG.debug("Could not run change " + change, x);
        }
    }

    @Override
    public void run()
    {
        _strategy.execute();
    }

    @Override
    public Runnable produce()
    {
        try
        {
            while (isRunning() || isStopping())
            {
                if (!_selections.hasNext())
                {
                    // Do we have selected keys?
                    if (!_selectedKeys.isEmpty())
                    {
                        // Yes, then update those keys.
                        for (SelectionKey key : _selectedKeys)
                            updateKey(key);
                        _selectedKeys.clear();
                    }

                    runChangesAndSetSelecting();

                    selectAndSetProcessing();
                }

                // Process any selected keys
                while (_selections.hasNext())
                {
                    SelectionKey key = _selections.next();

                    if (key.isValid())
                    {
                        Object attachment = key.attachment();
                        try
                        {
                            if (attachment instanceof SelectableEndPoint)
                            {
                                // Try to produce a task
                                Runnable task = ((SelectableEndPoint)attachment).onSelected();
                                if (task != null)
                                    return task;
                            }
                            else if (key.isConnectable())
                            {
                                processConnect(key, (Connect)attachment);
                            }
                            else if (key.isAcceptable())
                            {
                                processAccept(key);
                            }
                            else
                            {
                                throw new IllegalStateException();
                            }
                        }
                        catch (CancelledKeyException x)
                        {
                            LOG.debug("Ignoring cancelled key for channel {}", key.channel());
                            if (attachment instanceof EndPoint)
                                closeNoExceptions((EndPoint)attachment);
                        }
                        catch (Throwable x)
                        {
                            LOG.warn("Could not process key for channel " + key.channel(), x);
                            if (attachment instanceof EndPoint)
                                closeNoExceptions((EndPoint)attachment);
                        }
                    }
                    else
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Selector loop ignoring invalid key for channel {}", key.channel());
                        Object attachment = key.attachment();
                        if (attachment instanceof EndPoint)
                            ((EndPoint)attachment).close();
                    }
                }
            }
            return null;
        }
        catch (Throwable x)
        {
            if (isRunning())
                LOG.warn(x);
            else
                LOG.ignore(x);
            return null;
        }
    }

    private void runChangesAndSetSelecting()
    {
        // Run the changes, and only exit if we ran all changes
        loop:
        while (true)
        {
            State state = _state.get();
            switch (state)
            {
                case PROCESSING:
                    // We can loop on _runChanges list without lock, because only access here.
                    int size = _runChanges.size();
                    for (int i = 0; i < size; i++)
                        runChange(_runChanges.get(i));
                    _runChanges.clear();

                    if (!_state.compareAndSet(state, State.LOCKED))
                        continue;

                    // Do we have new changes?
                    if (_addChanges.isEmpty())
                    {
                        // No, so lets go selecting.
                        _state.set(State.SELECTING);
                        break loop;
                    }

                    // We have changes, so switch add/run lists and keep processing.
                    List<Runnable> tmp = _runChanges;
                    _runChanges = _addChanges;
                    _addChanges = tmp;
                    _state.set(State.PROCESSING);
                    continue;

                case LOCKED:
                    Thread.yield();
                    continue;

                default:
                    throw new IllegalStateException();
            }
        }
    }

    private void selectAndSetProcessing() throws IOException
    {
        // Do the selecting!
        if (LOG.isDebugEnabled())
            LOG.debug("Selector loop waiting on select");
        int selected = _selector.select();
        if (LOG.isDebugEnabled())
            LOG.debug("Selector loop woken up from select, {}/{} selected", selected, _selector.keys().size());

        // We have finished selecting.  This while loop could probably be replaced with just 
        // _state.compareAndSet(State.SELECTING, State.PROCESSING)
        // since if state is locked by submit, the resulting state will be PROCESSING anyway.
        // But let's be thorough and do the full loop.
        out:
        while (true)
        {
            switch (_state.get())
            {
                case SELECTING:
                    // We were still in selecting state, so probably have
                    // selected a key, so goto processing state to handle.
                    if (_state.compareAndSet(State.SELECTING, State.PROCESSING))
                        continue;
                    break out;
                case PROCESSING:
                    // We were already in processing, so were woken up by a change being
                    // submitted, so no state change needed - lets just process.
                    break out;
                case LOCKED:
                    // A change is currently being submitted. This does not matter
                    // here so much, but we will spin anyway so we don't race it later
                    // nor overwrite its state change.
                    Thread.yield();
                    continue;
                default:
                    throw new IllegalStateException();
            }
        }

        _selectedKeys = _selector.selectedKeys();
        _selections = _selectedKeys.iterator();
    }

    private void updateKey(SelectionKey key)
    {
        Object attachment = key.attachment();
        if (attachment instanceof SelectableEndPoint)
            ((SelectableEndPoint)attachment).updateKey();
    }

    private void processConnect(SelectionKey key, Connect connect)
    {
        SocketChannel channel = (SocketChannel)key.channel();
        try
        {
            key.attach(connect.attachment);
            boolean connected = _selectorManager.finishConnect(channel);
            if (connected)
            {
                connect.timeout.cancel();
                key.interestOps(0);
                EndPoint endpoint = createEndPoint(channel, key);
                key.attach(endpoint);
            }
            else
            {
                throw new ConnectException();
            }
        }
        catch (Throwable x)
        {
            connect.failed(x);
        }
    }

    private void processAccept(SelectionKey key)
    {
        ServerSocketChannel server = (ServerSocketChannel)key.channel();
        SocketChannel channel = null;
        try
        {
            while ((channel = server.accept()) != null)
            {
                _selectorManager.accepted(channel);
            }
        }
        catch (Throwable x)
        {
            closeNoExceptions(channel);
            LOG.warn("Accept failed for channel " + channel, x);
        }
    }

    private void closeNoExceptions(Closeable closeable)
    {
        try
        {
            if (closeable != null)
                closeable.close();
        }
        catch (Throwable x)
        {
            LOG.ignore(x);
        }
    }

    private EndPoint createEndPoint(SocketChannel channel, SelectionKey selectionKey) throws IOException
    {
        EndPoint endPoint = _selectorManager.newEndPoint(channel, this, selectionKey);
        _selectorManager.endPointOpened(endPoint);
        Connection connection = _selectorManager.newConnection(channel, endPoint, selectionKey.attachment());
        endPoint.setConnection(connection);
        _selectorManager.connectionOpened(connection);
        if (LOG.isDebugEnabled())
            LOG.debug("Created {}", endPoint);
        return endPoint;
    }

    public void destroyEndPoint(EndPoint endPoint)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Destroyed {}", endPoint);
        Connection connection = endPoint.getConnection();
        if (connection != null)
            _selectorManager.connectionClosed(connection);
        _selectorManager.endPointClosed(endPoint);
    }

    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        out.append(String.valueOf(this)).append(" id=").append(String.valueOf(_id)).append("\n");

        Selector selector = _selector;
        if (selector != null && selector.isOpen())
        {
            final ArrayList<Object> dump = new ArrayList<>(selector.keys().size() * 2);

            DumpKeys dumpKeys = new DumpKeys(dump);
            submit(dumpKeys);
            dumpKeys.await(5, TimeUnit.SECONDS);

            ContainerLifeCycle.dump(out, indent, dump);
        }
    }

    public void dumpKeysState(List<Object> dumps)
    {
        Selector selector = _selector;
        Set<SelectionKey> keys = selector.keys();
        dumps.add(selector + " keys=" + keys.size());
        for (SelectionKey key : keys)
        {
            if (key.isValid())
                dumps.add(key.attachment() + " iOps=" + key.interestOps() + " rOps=" + key.readyOps());
            else
                dumps.add(key.attachment() + " iOps=-1 rOps=-1");
        }
    }


    @Override
    public String toString()
    {
        Selector selector = _selector;
        return String.format("%s keys=%d selected=%d",
                super.toString(),
                selector != null && selector.isOpen() ? selector.keys().size() : -1,
                selector != null && selector.isOpen() ? selector.selectedKeys().size() : -1);
    }

    private class DumpKeys implements Runnable
    {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final List<Object> _dumps;

        private DumpKeys(List<Object> dumps)
        {
            this._dumps = dumps;
        }

        @Override
        public void run()
        {
            dumpKeysState(_dumps);
            latch.countDown();
        }

        public boolean await(long timeout, TimeUnit unit)
        {
            try
            {
                return latch.await(timeout, unit);
            }
            catch (InterruptedException x)
            {
                return false;
            }
        }
    }

    class Acceptor implements Runnable
    {
        private final ServerSocketChannel _channel;

        public Acceptor(ServerSocketChannel channel)
        {
            this._channel = channel;
        }

        @Override
        public void run()
        {
            try
            {
                SelectionKey key = _channel.register(_selector, SelectionKey.OP_ACCEPT, null);
                if (LOG.isDebugEnabled())
                    LOG.debug("{} acceptor={}", this, key);
            }
            catch (Throwable x)
            {
                closeNoExceptions(_channel);
                LOG.warn(x);
            }
        }
    }

    class Accept implements Runnable
    {
        private final SocketChannel channel;
        private final Object attachment;

        Accept(SocketChannel channel, Object attachment)
        {
            this.channel = channel;
            this.attachment = attachment;
        }

        @Override
        public void run()
        {
            try
            {
                SelectionKey key = channel.register(_selector, 0, attachment);
                EndPoint endpoint = createEndPoint(channel, key);
                key.attach(endpoint);
            }
            catch (Throwable x)
            {
                closeNoExceptions(channel);
                LOG.debug(x);
            }
        }
    }

    class Connect implements Runnable
    {
        private final AtomicBoolean failed = new AtomicBoolean();
        private final SocketChannel channel;
        private final Object attachment;
        private final Scheduler.Task timeout;

        Connect(SocketChannel channel, Object attachment)
        {
            this.channel = channel;
            this.attachment = attachment;
            this.timeout = ManagedSelector.this._selectorManager.getScheduler().schedule(new ConnectTimeout(this), ManagedSelector.this._selectorManager.getConnectTimeout(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void run()
        {
            try
            {
                channel.register(_selector, SelectionKey.OP_CONNECT, this);
            }
            catch (Throwable x)
            {
                failed(x);
            }
        }

        private void failed(Throwable failure)
        {
            if (failed.compareAndSet(false, true))
            {
                timeout.cancel();
                closeNoExceptions(channel);
                ManagedSelector.this._selectorManager.connectionFailed(channel, failure, attachment);
            }
        }
    }

    private class ConnectTimeout implements Runnable
    {
        private final Connect connect;

        private ConnectTimeout(Connect connect)
        {
            this.connect = connect;
        }

        @Override
        public void run()
        {
            SocketChannel channel = connect.channel;
            if (channel.isConnectionPending())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Channel {} timed out while connecting, closing it", channel);
                connect.failed(new SocketTimeoutException());
            }
        }
    }

    private class Stop implements Runnable
    {
        private final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void run()
        {
            try
            {
                for (SelectionKey key : _selector.keys())
                {
                    Object attachment = key.attachment();
                    if (attachment instanceof EndPoint)
                    {
                        EndPointCloser closer = new EndPointCloser((EndPoint)attachment);
                        ManagedSelector.this._selectorManager.execute(closer);
                        // We are closing the SelectorManager, so we want to block the
                        // selector thread here until we have closed all EndPoints.
                        // This is different than calling close() directly, because close()
                        // can wait forever, while here we are limited by the stop timeout.
                        closer.await(getStopTimeout());
                    }
                }

                closeNoExceptions(_selector);
            }
            finally
            {
                latch.countDown();
            }
        }

        public boolean await(long timeout)
        {
            try
            {
                return latch.await(timeout, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException x)
            {
                return false;
            }
        }
    }

    private class EndPointCloser implements Runnable
    {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final EndPoint endPoint;

        private EndPointCloser(EndPoint endPoint)
        {
            this.endPoint = endPoint;
        }

        @Override
        public void run()
        {
            try
            {
                closeNoExceptions(endPoint.getConnection());
            }
            finally
            {
                latch.countDown();
            }
        }

        private boolean await(long timeout)
        {
            try
            {
                return latch.await(timeout, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException x)
            {
                return false;
            }
        }
    }

    private enum State
    {
        PROCESSING, SELECTING, LOCKED
    }

}
