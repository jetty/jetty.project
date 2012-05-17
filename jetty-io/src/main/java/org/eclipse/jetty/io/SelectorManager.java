// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
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
import java.net.ConnectException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.Name;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/**
 * The Selector Manager manages and number of SelectSets to allow
 * NIO scheduling to scale to large numbers of connections.
 * <p>
 */
public abstract class SelectorManager extends AbstractLifeCycle implements Dumpable
{
    public static final Logger LOG = Log.getLogger(SelectorManager.class);

    private final ManagedSelector[] _selectSets;
    private long _selectSetIndex;

    protected SelectorManager()
    {
        this((Runtime.getRuntime().availableProcessors()+1)/2);
    }

    protected SelectorManager(@Name("selectors") int selectors)
    {
        this._selectSets = new ManagedSelector[selectors];
    }


    /**
     * @return the max idle time
     */
    protected abstract int getMaxIdleTime();
    
    protected abstract void execute(Runnable task);

    /**
     * @return the number of select sets in use
     */
    public int getSelectSets()
    {
        return _selectSets.length;
    }

    private ManagedSelector chooseSelectSet()
    {
        // The ++ increment here is not atomic, but it does not matter.
        // so long as the value changes sometimes, then connections will
        // be distributed over the available sets.
        long s = _selectSetIndex++;
        int index = (int)(s % getSelectSets());
        return _selectSets[index];
    }

    /**
     * Registers a channel
     * @param channel the channel to register
     * @param attachment Attached Object
     */
    public void connect(SocketChannel channel, Object attachment)
    {
        ManagedSelector set = chooseSelectSet();
        set.submit(set.new Connect(channel, attachment));
    }

    /**
     * Registers a channel
     * @param channel the channel to register
     */
    public void accept(final SocketChannel channel)
    {
        final ManagedSelector set = chooseSelectSet();
        set.submit(set.new Accept(channel));
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        for (int i=0;i< _selectSets.length;i++)
        {
            ManagedSelector selectSet = newSelectSet(i);
            _selectSets[i] = selectSet;
            selectSet.start();
            execute(selectSet);
            execute(new Expirer());
        }
    }

    protected ManagedSelector newSelectSet(int id)
    {
        return new ManagedSelector(id);
    }

    @Override
    protected void doStop() throws Exception
    {
        for (ManagedSelector set : _selectSets)
            set.stop();
        super.doStop();
    }

    /**
     * @param endpoint the endPoint being opened
     */
    protected abstract void endPointOpened(AsyncEndPoint endpoint);

    /**
     * @param endpoint the endPoint being closed
     */
    protected abstract void endPointClosed(AsyncEndPoint endpoint);

    /**
     * @param endpoint the endPoint being upgraded
     * @param oldConnection the previous connection
     */
    protected abstract void endPointUpgraded(AsyncEndPoint endpoint,AsyncConnection oldConnection);

    /**
     * @param channel the socket channel
     * @param endpoint the endPoint
     * @param attachment the attachment
     * @return a new connection
     */
    public abstract AsyncConnection newConnection(SocketChannel channel, AsyncEndPoint endpoint, Object attachment);

    /**
     * Create a new end point
     * @param channel the socket channel
     * @param selectSet the select set the channel is registered to
     * @param sKey the selection key
     * @return the new endpoint {@link SelectChannelEndPoint}
     * @throws IOException if the endPoint cannot be created
     */
    protected abstract SelectableAsyncEndPoint newEndPoint(SocketChannel channel, SelectorManager.ManagedSelector selectSet, SelectionKey sKey) throws IOException;

    protected void connectionFailed(SocketChannel channel, Throwable ex, Object attachment)
    {
        LOG.warn(String.format("%s - %s", channel, attachment), ex);
    }

    public String dump()
    {
        return AggregateLifeCycle.dump(this);
    }

    public void dump(Appendable out, String indent) throws IOException
    {
        AggregateLifeCycle.dumpObject(out,this);
        AggregateLifeCycle.dump(out, indent, TypeUtil.asList(_selectSets));
    }

    private class Expirer implements Runnable
    {
        @Override
        public void run()
        {
            while (isRunning())
            {
                for (ManagedSelector selector : _selectSets)
                    if (selector!=null)
                        selector.timeoutCheck();
                sleep(1000);
            }
        }

        private void sleep(long delay)
        {
            try
            {
                Thread.sleep(delay);
            }
            catch (InterruptedException x)
            {
                LOG.ignore(x);
            }
        }
    }

    public class ManagedSelector extends AbstractLifeCycle implements Runnable, Dumpable
    {
        private final ConcurrentLinkedQueue<Runnable> _changes = new ConcurrentLinkedQueue<>();
        private ConcurrentMap<SelectableAsyncEndPoint,Object> _endPoints = new ConcurrentHashMap<>();
        private final int _id;
        private Selector _selector;
        private Thread _thread;
        private boolean needsWakeup = true;

        protected ManagedSelector(int id)
        {
            _id = id;
        }

        @Override
        protected void doStart() throws Exception
        {
            super.doStart();
            _selector = Selector.open();
        }

        @Override
        protected void doStop() throws Exception
        {
            Stop task = new Stop();
            submit(task);
            task.await(getStopTimeout(), TimeUnit.MILLISECONDS);
        }

        public boolean submit(Runnable change)
        {
            if (Thread.currentThread() != _thread)
            {
                _changes.add(change);
                if (LOG.isDebugEnabled())
                    LOG.debug("Queued change {}", change);
                boolean wakeup = needsWakeup;
                if (wakeup)
                    wakeup();
                return false;
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Submitted change {}", change);
                runChanges();
                runChange(change);
                return true;
            }
        }

        private void runChanges()
        {
            Runnable change;
            while ((change = _changes.poll()) != null)
            {
                runChange(change);
            }
        }

        protected void runChange(Runnable change)
        {
            LOG.debug("Running change {}", change);
            change.run();
        }

        @Override
        public void run()
        {
            _thread = Thread.currentThread();
            String name = _thread.getName();
            try
            {
                _thread.setName(name + " Selector" + _id);
                LOG.debug("Starting {} on {}", _thread, this);
                while (isRunning())
                {
                    try
                    {
                        doSelect();
                    }
                    catch (IOException e)
                    {
                        LOG.warn(e);
                    }
                }
                processChanges();
            }
            finally
            {
                LOG.debug("Stopped {} on {}", _thread, this);
                _thread.setName(name);
            }
        }

        /**
         * Select and execute tasks found from changes and the selector.
         *
         * @throws IOException
         */
        public void doSelect() throws IOException
        {
            boolean debug = LOG.isDebugEnabled();
            try
            {
                processChanges();

                if (debug)
                    LOG.debug("Selector loop waiting on select");
                int selected = _selector.select();
                if (debug)
                    LOG.debug("Selector loop woken up from select, {}/{} selected", selected, _selector.keys().size());

                needsWakeup = false;

                Set<SelectionKey> selectedKeys = _selector.selectedKeys();
                for (SelectionKey key: selectedKeys)
                {
                    try
                    {
                        if (!key.isValid())
                        {
                            if (debug)
                                LOG.debug("Selector loop ignoring invalid key for channel {}", key.channel());
                            continue;
                        }

                        processKey(key);
                    }
                    catch (Exception x)
                    {
                        if (isRunning())
                            LOG.warn(x);
                        else
                            LOG.debug(x);

                        execute(new Close(key));
                    }
                }

                // Everything always handled
                selectedKeys.clear();
            }
            catch (ClosedSelectorException x)
            {
                if (isRunning())
                    LOG.warn(x);
                else
                    LOG.ignore(x);
            }
        }

        private void processChanges()
        {
            runChanges();

            // If tasks are submitted between these 2 statements, they will not
            // wakeup the selector, therefore below we run again the tasks

            needsWakeup = true;

            // Run again the tasks to avoid the race condition where a task is
            // submitted but will not wake up the selector
            runChanges();
        }

        private void processKey(SelectionKey key) throws IOException
        {
            try
            {
                Object att = key.attachment();
                if (att instanceof SelectableAsyncEndPoint)
                {
                    if (key.isReadable() || key.isWritable())
                        ((SelectableAsyncEndPoint)att).onSelected();
                }
                else if (key.isConnectable())
                {
                    // Complete a connection of a registered channel
                    SocketChannel channel = (SocketChannel)key.channel();
                    try
                    {
                        boolean connected = channel.finishConnect();
                        if (connected)
                        {
                            AsyncEndPoint endpoint = createEndPoint(channel, key);
                            key.attach(endpoint);
                        }
                        else
                        {
                            throw new ConnectException();
                        }
                    }
                    catch (Exception x)
                    {
                        connectionFailed(channel, x, att);
                        key.cancel();
                    }
                }
                else
                {
                    throw new IllegalStateException();
                }
            }
            catch (CancelledKeyException x)
            {
                LOG.debug("Ignoring cancelled key for channel", key.channel());
            }
        }

        public SelectorManager getManager()
        {
            return SelectorManager.this;
        }

        public void wakeup()
        {
            _selector.wakeup();
        }

        private AsyncEndPoint createEndPoint(SocketChannel channel, SelectionKey sKey) throws IOException
        {
            SelectableAsyncEndPoint endp = newEndPoint(channel, this, sKey);
            _endPoints.put(endp, this);
            LOG.debug("Created {}", endp);
            endPointOpened(endp);
            return endp;
        }


        public void destroyEndPoint(SelectableAsyncEndPoint endp)
        {
            LOG.debug("Destroyed {}", endp);
            _endPoints.remove(endp);
            endPointClosed(endp);
        }

        // TODO: remove
        Selector getSelector()
        {
            return _selector;
        }

        public String dump()
        {
            return AggregateLifeCycle.dump(this);
        }


        public void dump(Appendable out, String indent) throws IOException
        {
            out.append(String.valueOf(this)).append(" id=").append(String.valueOf(_id)).append("\n");

            Thread selecting = _thread;

            Object where = "not selecting";
            StackTraceElement[] trace =selecting==null?null:selecting.getStackTrace();
            if (trace!=null)
            {
                for (StackTraceElement t:trace)
                    if (t.getClassName().startsWith("org.eclipse.jetty."))
                    {
                        where=t;
                        break;
                    }
            }

            Selector selector=_selector;
            if (selector!=null)
            {
                final ArrayList<Object> dump = new ArrayList<>(selector.keys().size()*2);
                dump.add(where);

                DumpKeys dumpKeys = new DumpKeys(dump);
                submit(dumpKeys);
                dumpKeys.await(5, TimeUnit.SECONDS);

                AggregateLifeCycle.dump(out,indent,dump);
            }
        }


        public void dumpKeysState(List<Object> dumpto)
        {
            Selector selector=_selector;
            Set<SelectionKey> keys = selector.keys();
            dumpto.add(selector + " keys=" + keys.size());
            for (SelectionKey key: keys)
            {
                if (key.isValid())
                    dumpto.add(key.attachment()+" iOps="+key.interestOps()+" rOps="+key.readyOps());
                else
                    dumpto.add(key.attachment()+" iOps=-1 rOps=-1");
            }
        }

        public String toString()
        {
            Selector selector=_selector;
            return String.format("%s keys=%d selected=%d",
                    super.toString(),
                    selector != null && selector.isOpen() ? selector.keys().size() : -1,
                    selector != null && selector.isOpen() ? selector.selectedKeys().size() : -1);
        }

        private void timeoutCheck()
        {
            long now = System.currentTimeMillis();
            for (SelectableAsyncEndPoint endPoint : _endPoints.keySet())
                endPoint.checkReadWriteTimeout(now);
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

        private class Accept implements Runnable
        {
            private final SocketChannel _channel;

            public Accept(SocketChannel channel)
            {
                this._channel = channel;
            }

            @Override
            public void run()
            {
                try
                {
                    SelectionKey key = _channel.register(_selector, 0, null);
                    AsyncEndPoint endpoint = createEndPoint(_channel, key);
                    key.attach(endpoint);
                }
                catch (IOException x)
                {
                    LOG.debug(x);
                }
            }
        }

        private class Connect implements Runnable
        {
            private final SocketChannel channel;
            private final Object attachment;

            public Connect(SocketChannel channel, Object attachment)
            {
                this.channel = channel;
                this.attachment = attachment;
            }

            @Override
            public void run()
            {
                try
                {
                    channel.register(_selector, SelectionKey.OP_CONNECT, attachment);
                }
                catch (ClosedChannelException x)
                {
                    LOG.debug(x);
                }
            }
        }

        private class Close implements Runnable
        {
            private final SelectionKey key;

            private Close(SelectionKey key)
            {
                this.key = key;
            }

            @Override
            public void run()
            {
                try
                {
                    key.channel().close();
                }
                catch (IOException x)
                {
                    LOG.ignore(x);
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
                            EndPoint endpoint = (EndPoint)attachment;
                            endpoint.close();
                        }
                    }

                    _selector.close();
                }
                catch (IOException x)
                {
                    LOG.ignore(x);
                }
                finally
                {
                    latch.countDown();
                }
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
    }


    // TODO review this interface
    public interface SelectableAsyncEndPoint extends AsyncEndPoint
    {
        void onSelected();

        Channel getChannel();

        void doUpdateKey();

        void checkReadWriteTimeout(long idle_now);
    }

}
