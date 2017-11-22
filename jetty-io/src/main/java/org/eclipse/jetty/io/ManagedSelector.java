//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Locker;
import org.eclipse.jetty.util.thread.ReservedThreadExecutor;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.strategy.EatWhatYouKill;

/**
 * <p>{@link ManagedSelector} wraps a {@link Selector} simplifying non-blocking operations on channels.</p>
 * <p>{@link ManagedSelector} runs the select loop, which waits on {@link Selector#select()} until events
 * happen for registered channels. When events happen, it notifies the {@link EndPoint} associated
 * with the channel.</p>
 */
public class ManagedSelector extends ContainerLifeCycle implements Dumpable
{
    private static final Logger LOG = Log.getLogger(ManagedSelector.class);

    private final Locker _locker = new Locker();
    private boolean _selecting = false;
    private final Queue<Runnable> _actions = new ArrayDeque<>();
    private final SelectorManager _selectorManager;
    private final int _id;
    private final ExecutionStrategy _strategy;
    private Selector _selector;
    private int _actionCount;

    public ManagedSelector(SelectorManager selectorManager, int id)
    {
        _selectorManager = selectorManager;
        _id = id;
        SelectorProducer producer = new SelectorProducer();
        Executor executor = selectorManager.getExecutor();
        _strategy = new EatWhatYouKill(producer,executor,_selectorManager.getBean(ReservedThreadExecutor.class));
        addBean(_strategy,true);
        setStopTimeout(5000);
    }

    public Selector getSelector()
    {
        return _selector;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();

        _selector = _selectorManager.newSelector();

        // The producer used by the strategies will never
        // be idle (either produces a task or blocks).

        // The normal strategy obtains the produced task, schedules
        // a new thread to produce more, runs the task and then exits.
        _selectorManager.execute(_strategy::produce);
    }

    public int size()
    {
        Selector s = _selector;
        if (s == null)
            return 0;
        return s.keys().size();
    }

    @Override
    protected void doStop() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Stopping {}", this);
        CloseEndPoints close_endps = new CloseEndPoints();
        submit(close_endps);
        close_endps.await(getStopTimeout());
        CloseSelector close_selector = new CloseSelector();
        submit(close_selector);
        close_selector.await(getStopTimeout());

        super.doStop();
        
        if (LOG.isDebugEnabled())
            LOG.debug("Stopped {}", this);
    }

    public void submit(Runnable change)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Queued change {} on {}", change, this);

        Selector selector = null;
        try (Locker.Lock lock = _locker.lock())
        {
            _actions.offer(change);
            
            if (_selecting)
            {
                selector = _selector;
                // To avoid the extra select wakeup.
                _selecting = false;
            }
        }
        if (selector != null)
            selector.wakeup();
    }

    private Runnable processConnect(SelectionKey key, final Connect connect)
    {
        SelectableChannel channel = key.channel();
        try
        {
            key.attach(connect.attachment);
            boolean connected = _selectorManager.doFinishConnect(channel);
            if (LOG.isDebugEnabled())
                LOG.debug("Connected {} {}", connected, channel);
            if (connected)
            {
                if (connect.timeout.cancel())
                {
                    key.interestOps(0);
                    return new CreateEndPoint(channel, key)
                    {
                        @Override
                        protected void failed(Throwable failure)
                        {
                            super.failed(failure);
                            connect.failed(failure);
                        }
                    };
                }
                else
                {
                    throw new SocketTimeoutException("Concurrent Connect Timeout");
                }
            }
            else
            {
                throw new ConnectException();
            }
        }
        catch (Throwable x)
        {
            connect.failed(x);
            return null;
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

    private void createEndPoint(SelectableChannel channel, SelectionKey selectionKey) throws IOException
    {
        EndPoint endPoint = _selectorManager.newEndPoint(channel, this, selectionKey);
        Connection connection = _selectorManager.newConnection(channel, endPoint, selectionKey.attachment());
        endPoint.setConnection(connection);
        selectionKey.attach(endPoint);
        endPoint.onOpen();
        _selectorManager.endPointOpened(endPoint);
        _selectorManager.connectionOpened(connection);
        if (LOG.isDebugEnabled())
            LOG.debug("Created {}", endPoint);
    }

    public void destroyEndPoint(final EndPoint endPoint)
    {
        submit(new DestroyEndPoint(endPoint));
    }

    private int getActionSize()
    {
        try (Locker.Lock lock = _locker.lock())
        {
            return _actions.size();
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        super.dump(out, indent);
        Selector selector = _selector;
        if (selector != null && selector.isOpen())
        {
            List<Runnable> actions;
            try (Locker.Lock lock = _locker.lock())
            {
                actions = new ArrayList<>(_actions);
            }
            List<Object> keys = new ArrayList<>(selector.keys().size());
            DumpKeys dumpKeys = new DumpKeys(keys);
            submit(dumpKeys);
            dumpKeys.await(5, TimeUnit.SECONDS);
            dump(out, indent, Arrays.asList(new DumpableCollection("keys", keys), new DumpableCollection("actions", actions)));
        }
    }

    @Override
    public String toString()
    {
        Selector selector = _selector;
        return String.format("%s id=%s keys=%d selected=%d actions=%d",
                super.toString(),
                _id,
                selector != null && selector.isOpen() ? selector.keys().size() : -1,
                selector != null && selector.isOpen() ? selector.selectedKeys().size() : -1,
                getActionSize());
    }

    /**
     * A {@link Selectable} is an {@link EndPoint} that wish to be
     * notified of non-blocking events by the {@link ManagedSelector}.
     */
    public interface Selectable
    {
        /**
         * Callback method invoked when a read or write events has been
         * detected by the {@link ManagedSelector} for this endpoint.
         *
         * @return a job that may block or null
         */
        Runnable onSelected();

        /**
         * Callback method invoked when all the keys selected by the
         * {@link ManagedSelector} for this endpoint have been processed.
         */
        void updateKey();
    }

    private class SelectorProducer implements ExecutionStrategy.Producer
    {
        private Set<SelectionKey> _keys = Collections.emptySet();
        private Iterator<SelectionKey> _cursor = Collections.emptyIterator();

        @Override
        public Runnable produce()
        {
            while (true)
            {
                Runnable task = processSelected();
                if (task != null)
                    return task;

                Runnable action = nextAction();
                if (action != null)
                    return action;

                updateKeys();

                if (!select())
                    return null;
            }
        }

        private Runnable nextAction()
        {
            Selector selector = null;
            Runnable action = null;
            try (Locker.Lock lock = _locker.lock())
            {
                // It is important to avoid live-lock (busy blocking) here.  If too many actions
                // are submitted, this can indefinitely defer selection happening.   Similarly if 
                // we give too much priority to selection, it may prevent actions from being run.
                // The solution implemented here is to only process the number of actions that were
                // originally in the action queue before attempting a select
                
                if (_actionCount==0)
                {               
                    // Calculate how many actions we are prepared to handle before selection
                    _actionCount = _actions.size();
                    if (_actionCount>0)
                        action = _actions.poll();
                    else
                        _selecting = true;
                }
                else if (_actionCount==1)
                {
                    _actionCount = 0;
                    
                    if (LOG.isDebugEnabled())
                        LOG.debug("Forcing selection, actions={}",_actions.size());
                    
                    if (_actions.size()==0)
                    {
                        // This was the last action, so select normally
                        _selecting = true;
                    }
                    else
                    {
                        // there are still more actions to handle, so
                        // immediately wake up (as if remaining action were just added).
                        selector = _selector;
                        _selecting = false;
                    }
                }
                else
                {
                    _actionCount--;
                    action = _actions.poll();
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("action={} wakeup={}",action,selector!=null);
            
            if (selector != null)
                selector.wakeup();

            return action;
        }

        private boolean select()
        {
            try
            {
                Selector selector = _selector;
                if (selector != null && selector.isOpen())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Selector {} waiting on select", selector);
                    int selected = selector.select();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Selector {} woken up from select, {}/{} selected", selector, selected, selector.keys().size());

                    int actions;
                    try (Locker.Lock lock = _locker.lock())
                    {
                        // finished selecting
                        _selecting = false;
                        actions = _actions.size();
                    }

                    _keys = selector.selectedKeys();
                    _cursor = _keys.iterator();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Selector {} processing {} keys, {} actions", selector, _keys.size(), actions);

                    return true;
                }
            }
            catch (Throwable x)
            {
                closeNoExceptions(_selector);
                if (isRunning())
                    LOG.warn(x);
                else
                    LOG.debug(x);
            }
            return false;
        }

        private Runnable processSelected()
        {
            while (_cursor.hasNext())
            {
                SelectionKey key = _cursor.next();
                if (key.isValid())
                {
                    Object attachment = key.attachment();
                    if (LOG.isDebugEnabled())
                        LOG.debug("selected {} {} ",key,attachment);
                    try
                    {
                        if (attachment instanceof Selectable)
                        {
                            // Try to produce a task
                            Runnable task = ((Selectable)attachment).onSelected();
                            if (task != null)
                                return task;
                        }
                        else if (key.isConnectable())
                        {
                            Runnable task = processConnect(key, (Connect)attachment);
                            if (task != null)
                                return task;
                        }
                        else
                        {
                            throw new IllegalStateException("key=" + key + ", att=" + attachment + ", iOps=" + key.interestOps() + ", rOps=" + key.readyOps());
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
                        closeNoExceptions((EndPoint)attachment);
                }
            }
            return null;
        }

        private void updateKeys()
        {
            // Do update keys for only previously selected keys.
            // This will update only those keys whose selection did not cause an
            // updateKeys action to be submitted.
            for (SelectionKey key : _keys)
                updateKey(key);
            _keys.clear();
        }

        private void updateKey(SelectionKey key)
        {
            Object attachment = key.attachment();
            if (attachment instanceof Selectable)
                ((Selectable)attachment).updateKey();
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x", getClass().getSimpleName(), hashCode());
        }
    }

    private class DumpKeys extends Invocable.NonBlocking
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
            Selector selector = _selector;
            if (selector != null && selector.isOpen())
            {
                Set<SelectionKey> keys = selector.keys();
                _dumps.add(selector + " keys=" + keys.size());
                for (SelectionKey key : keys)
                {
                    try
                    {
                        _dumps.add(String.format("SelectionKey@%x{i=%d}->%s", key.hashCode(), key.interestOps(), key.attachment()));
                    }
                    catch (Throwable x)
                    {
                        _dumps.add(String.format("SelectionKey@%x[%s]->%s", key.hashCode(), x, key.attachment()));
                    }
                }
            }
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

    class Acceptor extends Invocable.NonBlocking implements Selectable, Closeable
    {
        private final SelectableChannel _channel;
        private SelectionKey _key;

        public Acceptor(SelectableChannel channel)
        {
            this._channel = channel;
        }

        @Override
        public void run()
        {
            try
            {
                if (_key==null)
                {
                    _key = _channel.register(_selector, SelectionKey.OP_ACCEPT, this);
                }     

                if (LOG.isDebugEnabled())
                    LOG.debug("{} acceptor={}", this, _key);
            }
            catch (Throwable x)
            {
                closeNoExceptions(_channel);
                LOG.warn(x);
            }
        }

        @Override
        public Runnable onSelected()
        {
            SelectableChannel server = _key.channel();
            SelectableChannel channel = null;
            try
            {
                while(true)
                {
                    channel = _selectorManager.doAccept(server);
                    if (channel==null)
                        break;
                    _selectorManager.accepted(channel);
                }
            }
            catch (Throwable x)
            {
                closeNoExceptions(channel);
                LOG.warn("Accept failed for channel " + channel, x);
            }
            
            return null;
        }

        @Override
        public void updateKey()
        {
        }

        @Override
        public void close() throws IOException
        {
            SelectionKey key = _key;
            _key = null;
            if (key!=null && key.isValid())
                key.cancel();
        }
    }

    class Accept extends Invocable.NonBlocking implements Closeable
    {
        private final SelectableChannel channel;
        private final Object attachment;

        Accept(SelectableChannel channel, Object attachment)
        {
            this.channel = channel;
            this.attachment = attachment;
        }

        @Override
        public void close()
        {
            LOG.debug("closed accept of {}", channel);
            closeNoExceptions(channel);
        }

        @Override
        public void run()
        {
            try
            {
                final SelectionKey key = channel.register(_selector, 0, attachment);
                submit(new CreateEndPoint(channel, key));
            }
            catch (Throwable x)
            {
                closeNoExceptions(channel);
                LOG.debug(x);
            }
        }
    }

    private class CreateEndPoint implements Runnable, Invocable, Closeable
    {
        private final SelectableChannel channel;
        private final SelectionKey key;

        public CreateEndPoint(SelectableChannel channel, SelectionKey key)
        {
            this.channel = channel;
            this.key = key;
        }

        @Override
        public void run()
        {
            try
            {
                createEndPoint(channel, key);
            }
            catch (Throwable x)
            {
                LOG.debug(x);
                failed(x);
            }
        }

        @Override
        public void close()
        {
            LOG.debug("closed creation of {}", channel);
            closeNoExceptions(channel);
        }

        protected void failed(Throwable failure)
        {
            closeNoExceptions(channel);
            LOG.warn(String.valueOf(failure));
            LOG.debug(failure);
        }
    }

    class Connect extends Invocable.NonBlocking
    {
        private final AtomicBoolean failed = new AtomicBoolean();
        private final SelectableChannel channel;
        private final Object attachment;
        private final Scheduler.Task timeout;

        Connect(SelectableChannel channel, Object attachment)
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

    private class ConnectTimeout extends Invocable.NonBlocking
    {
        private final Connect connect;

        private ConnectTimeout(Connect connect)
        {
            this.connect = connect;
        }

        @Override
        public void run()
        {
            SelectableChannel channel = connect.channel;
            if (_selectorManager.isConnectionPending(channel))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Channel {} timed out while connecting, closing it", channel);
                connect.failed(new SocketTimeoutException("Connect Timeout"));
            }
        }
    }

    private class CloseEndPoints extends Invocable.NonBlocking
    {
        private final CountDownLatch _latch = new CountDownLatch(1);
        private CountDownLatch _allClosed;

        @Override
        public void run()
        {
            List<EndPoint> end_points = new ArrayList<>();
            for (SelectionKey key : _selector.keys())
            {
                if (key.isValid())
                {
                    Object attachment = key.attachment();
                    if (attachment instanceof EndPoint)
                        end_points.add((EndPoint)attachment);
                }
            }

            int size = end_points.size();
            if (LOG.isDebugEnabled())
                LOG.debug("Closing {} endPoints on {}", size, ManagedSelector.this);

            _allClosed = new CountDownLatch(size);
            _latch.countDown();

            for (EndPoint endp : end_points)
                submit(new EndPointCloser(endp, _allClosed));

            if (LOG.isDebugEnabled())
                LOG.debug("Closed {} endPoints on {}", size, ManagedSelector.this);
        }

        public boolean await(long timeout)
        {
            try
            {
                return _latch.await(timeout, TimeUnit.MILLISECONDS) &&
                        _allClosed.await(timeout, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException x)
            {
                return false;
            }
        }
    }

    private class EndPointCloser implements Runnable
    {
        private final EndPoint _endPoint;
        private final CountDownLatch _latch;

        private EndPointCloser(EndPoint endPoint, CountDownLatch latch)
        {
            _endPoint = endPoint;
            _latch = latch;
        }

        @Override
        public void run()
        {
            closeNoExceptions(_endPoint.getConnection());
            _latch.countDown();
        }
    }

    private class CloseSelector extends Invocable.NonBlocking
    {
        private CountDownLatch _latch = new CountDownLatch(1);

        @Override
        public void run()
        {
            Selector selector = _selector;
            _selector = null;
            closeNoExceptions(selector);
            _latch.countDown();
        }

        public boolean await(long timeout)
        {
            try
            {
                return _latch.await(timeout, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException x)
            {
                return false;
            }
        }
    }

    private class DestroyEndPoint implements Runnable, Invocable, Closeable
    {
        private final EndPoint endPoint;

        public DestroyEndPoint(EndPoint endPoint)
        {
            this.endPoint = endPoint;
        }

        @Override
        public void run()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Destroyed {}", endPoint);
            Connection connection = endPoint.getConnection();
            if (connection != null)
                _selectorManager.connectionClosed(connection);
            _selectorManager.endPointClosed(endPoint);
        }

        @Override
        public void close()
        {
            run();
        }
    }
}
