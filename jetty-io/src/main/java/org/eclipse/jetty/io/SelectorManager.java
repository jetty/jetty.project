//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>{@link SelectorManager} manages a number of {@link ManagedSelector}s that
 * simplify the non-blocking primitives provided by the JVM via the {@code java.nio} package.</p>
 * <p>{@link SelectorManager} subclasses implement methods to return protocol-specific
 * {@link EndPoint}s and {@link Connection}s.</p>
 */
public abstract class SelectorManager extends AbstractLifeCycle implements Dumpable
{
    protected static final Logger LOG = Log.getLogger(SelectorManager.class);

    private final ManagedSelector[] _selectors;
    private volatile long _selectorIndex;

    protected SelectorManager()
    {
        this((Runtime.getRuntime().availableProcessors() + 1) / 2);
    }

    protected SelectorManager(@Name(value="selectors") int selectors)
    {
        _selectors = new ManagedSelector[selectors];
    }

    /**
     * Executes the given task in a different thread.
     *
     * @param task the task to execute
     */
    protected abstract void execute(Runnable task);

    /**
     * @return the number of selectors in use
     */
    public int getSelectorCount()
    {
        return _selectors.length;
    }

    private ManagedSelector chooseSelector()
    {
        // The ++ increment here is not atomic, but it does not matter,
        // so long as the value changes sometimes, then connections will
        // be distributed over the available selectors.
        long s = _selectorIndex++;
        int index = (int)(s % getSelectorCount());
        return _selectors[index];
    }

    /**
     * <p>Registers a channel to perform a non-blocking connect.</p>
     * <p>The channel must be set in non-blocking mode, and {@link SocketChannel#connect(SocketAddress)}
     * must be called prior to calling this method.</p>
     *
     * @param channel    the channel to register
     * @param attachment the attachment object
     */
    public void connect(SocketChannel channel, Object attachment)
    {
        ManagedSelector set = chooseSelector();
        set.submit(set.new Connect(channel, attachment));
    }

    /**
     * <p>Registers a channel to perform non-blocking read/write operations.</p>
     * <p>This method is called just after a channel has been accepted by {@link ServerSocketChannel#accept()},
     * or just after having performed a blocking connect via {@link Socket#connect(SocketAddress, int)}.</p>
     *
     * @param channel the channel to register
     */
    public void accept(final SocketChannel channel)
    {
        final ManagedSelector selector = chooseSelector();
        selector.submit(selector.new Accept(channel));
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        for (int i = 0; i < _selectors.length; i++)
        {
            ManagedSelector selector = newSelector(i);
            _selectors[i] = selector;
            selector.start();
            execute(selector);
        }
    }

    /**
     * <p>Factory method for {@link ManagedSelector}.</p>
     *
     * @param id an identifier for the {@link ManagedSelector to create}
     * @return a new {@link ManagedSelector}
     */
    protected ManagedSelector newSelector(int id)
    {
        return new ManagedSelector(id);
    }

    @Override
    protected void doStop() throws Exception
    {
        for (ManagedSelector selector : _selectors)
            selector.stop();
        super.doStop();
    }

    /**
     * <p>Callback method invoked when an endpoint is opened.</p>
     *
     * @param endpoint the endpoint being opened
     */
    protected void endPointOpened(EndPoint endpoint)
    {
        endpoint.onOpen();
    }

    /**
     * <p>Callback method invoked when an endpoint is closed.</p>
     *
     * @param endpoint the endpoint being closed
     */
    protected void endPointClosed(EndPoint endpoint)
    {
        endpoint.onClose();
    }

    /**
     * <p>Callback method invoked when a connection is opened.</p>
     *
     * @param connection the connection just opened
     */
    public void connectionOpened(Connection connection)
    {
        connection.onOpen();
    }

    /**
     * <p>Callback method invoked when a connection is closed.</p>
     *
     * @param connection the connection just closed
     */
    public void connectionClosed(Connection connection)
    {
        connection.onClose();
    }

    /**
     * <p>Callback method invoked when a connection is upgraded.</p>
     *
     * @param endpoint      the endpoint holding the new connection
     * @param oldConnection the previous connection
     */
    public void connectionUpgraded(EndPoint endpoint, Connection oldConnection)
    {
        connectionClosed(oldConnection);
        connectionOpened(endpoint.getConnection());
    }

    /**
     * <p>Callback method invoked when a non-blocking connect cannot be completed.</p>
     * <p>By default it just logs with level warning.</p>
     *
     * @param channel the channel that attempted the connect
     * @param ex the exception that caused the connect to fail
     * @param attachment the attachment object associated at registration
     */
    protected void connectionFailed(SocketChannel channel, Throwable ex, Object attachment)
    {
        LOG.warn(String.format("%s - %s", channel, attachment), ex);
    }

    /**
     * <p>Factory method to create {@link EndPoint}.</p>
     * <p>This method is invoked as a result of the registration of a channel via {@link #connect(SocketChannel, Object)}
     * or {@link #accept(SocketChannel)}.</p>
     *
     * @param channel   the channel associated to the endpoint
     * @param selector the selector the channel is registered to
     * @param selectionKey      the selection key
     * @return a new endpoint
     * @throws IOException if the endPoint cannot be created
     * @see #newConnection(SocketChannel, EndPoint, Object)
     */
    protected abstract EndPoint newEndPoint(SocketChannel channel, SelectorManager.ManagedSelector selector, SelectionKey selectionKey) throws IOException;

    /**
     * <p>Factory method to create {@link Connection}.</p>
     *
     * @param channel    the channel associated to the connection
     * @param endpoint   the endpoint
     * @param attachment the attachment
     * @return a new connection
     * @throws IOException
     * @see #newEndPoint(SocketChannel, ManagedSelector, SelectionKey)
     */
    public abstract Connection newConnection(SocketChannel channel, EndPoint endpoint, Object attachment) throws IOException;

    @Override
    public String dump()
    {
        return AggregateLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        AggregateLifeCycle.dumpObject(out, this);
        AggregateLifeCycle.dump(out, indent, TypeUtil.asList(_selectors));
    }

    /**
     * <p>{@link ManagedSelector} wraps a {@link Selector} simplifying non-blocking operations on channels.</p>
     * <p>{@link ManagedSelector} runs the select loop, which waits on {@link Selector#select()} until events
     * happen for registered channels. When events happen, it notifies the {@link EndPoint} associated
     * with the channel.</p>
     */
    public class ManagedSelector extends AbstractLifeCycle implements Runnable, Dumpable
    {
        private final Queue<Runnable> _changes = new ConcurrentLinkedQueue<>();
        private final int _id;
        private Selector _selector;
        private Thread _thread;
        private boolean _needsWakeup = true;

        public ManagedSelector(int id)
        {
            _id = id;
            setStopTimeout(5000);
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
            Stop stop = new Stop();
            submit(stop);
            stop.await(getStopTimeout());
        }

        /**
         * <p>Submits a change to be executed in the selector thread.</p>
         * <p>Changes may be submitted from any thread, and if they are submitted from a thread different
         * from the selector thread, they are queued for execution, and the selector thread woken up
         * (if necessary) to execute the change.</p>
         *
         * @param change the change to submit
         * @return true if the change has been executed, false if it has been queued for later execution
         */
        public boolean submit(Runnable change)
        {
            if (Thread.currentThread() != _thread)
            {
                _changes.offer(change);
                LOG.debug("Queued change {}", change);
                boolean wakeup = _needsWakeup;
                if (wakeup)
                    wakeup();
                return false;
            }
            else
            {
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
                runChange(change);
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
                _thread.setName(name + "-selector-" + _id);
                LOG.debug("Starting {} on {}", _thread, this);
                while (isRunning())
                    select();
                processChanges();
            }
            finally
            {
                LOG.debug("Stopped {} on {}", _thread, this);
                _thread.setName(name);
            }
        }

        /**
         * <p>Process changes and waits on {@link Selector#select()}.</p>
         *
         * @see #submit(Runnable)
         */
        public void select()
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

                _needsWakeup = false;

                Set<SelectionKey> selectedKeys = _selector.selectedKeys();
                for (SelectionKey key : selectedKeys)
                {
                    if (key.isValid())
                    {
                        processKey(key);
                    }
                    else
                    {
                        if (debug)
                            LOG.debug("Selector loop ignoring invalid key for channel {}", key.channel());
                        Object attachment = key.attachment();
                        if (attachment instanceof EndPoint)
                            ((EndPoint)attachment).close();
                    }
                }
                selectedKeys.clear();
            }
            catch (Exception x)
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

            _needsWakeup = true;

            // Run again the tasks to avoid the race condition where a task is
            // submitted but will not wake up the selector
            runChanges();
        }

        private void processKey(SelectionKey key)
        {
            Object attachment = key.attachment();
            try
            {
                if (attachment instanceof SelectableEndPoint)
                {
                    ((SelectableEndPoint)attachment).onSelected();
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
                            key.interestOps(0);
                            EndPoint endpoint = createEndPoint(channel, key);
                            key.attach(endpoint);
                        }
                        else
                        {
                            throw new ConnectException();
                        }
                    }
                    catch (Exception x)
                    {
                        connectionFailed(channel, x, attachment);
                        closeNoExceptions(channel);
                    }
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
                    ((EndPoint)attachment).close();
            }
            catch (Exception x)
            {
                LOG.warn("Could not process key for channel " + key.channel(), x);
                if (attachment instanceof EndPoint)
                    ((EndPoint)attachment).close();
            }
        }

        private void closeNoExceptions(Closeable closeable)
        {
            try
            {
                closeable.close();
            }
            catch (IOException x)
            {
                LOG.ignore(x);
            }
        }

        public SelectorManager getSelectorManager()
        {
            return SelectorManager.this;
        }

        public void wakeup()
        {
            _selector.wakeup();
        }

        private EndPoint createEndPoint(SocketChannel channel, SelectionKey selectionKey) throws IOException
        {
            EndPoint endPoint = newEndPoint(channel, this, selectionKey);
            endPointOpened(endPoint);
            Connection asyncConnection = newConnection(channel, endPoint, selectionKey.attachment());
            endPoint.setConnection(asyncConnection);
            connectionOpened(asyncConnection);
            LOG.debug("Created {}", endPoint);
            return endPoint;
        }

        public void destroyEndPoint(EndPoint endPoint)
        {
            LOG.debug("Destroyed {}", endPoint);
            Connection connection = endPoint.getConnection();
            if (connection != null)
                connectionClosed(connection);
            endPointClosed(endPoint);
        }

        @Override
        public String dump()
        {
            return AggregateLifeCycle.dump(this);
        }

        @Override
        public void dump(Appendable out, String indent) throws IOException
        {
            out.append(String.valueOf(this)).append(" id=").append(String.valueOf(_id)).append("\n");

            Thread selecting = _thread;

            Object where = "not selecting";
            StackTraceElement[] trace = selecting == null ? null : selecting.getStackTrace();
            if (trace != null)
            {
                for (StackTraceElement t : trace)
                    if (t.getClassName().startsWith("org.eclipse.jetty."))
                    {
                        where = t;
                        break;
                    }
            }

            Selector selector = _selector;
            if (selector != null)
            {
                final ArrayList<Object> dump = new ArrayList<>(selector.keys().size() * 2);
                dump.add(where);

                DumpKeys dumpKeys = new DumpKeys(dump);
                submit(dumpKeys);
                dumpKeys.await(5, TimeUnit.SECONDS);

                AggregateLifeCycle.dump(out, indent, dump);
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
                    EndPoint endpoint = createEndPoint(_channel, key);
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
                            execute(closer);
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
                    endPoint.getConnection().close();
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
    }

    /**
     * A {@link SelectableEndPoint} is an {@link EndPoint} that wish to be notified of
     * non-blocking events by the {@link ManagedSelector}.
     */
    public interface SelectableEndPoint extends EndPoint
    {
        /**
         * <p>Callback method invoked when a read or write events has been detected by the {@link ManagedSelector}
         * for this endpoint.</p>
         */
        void onSelected();
    }
}
