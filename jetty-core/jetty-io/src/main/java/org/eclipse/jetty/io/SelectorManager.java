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

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.EventListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.eclipse.jetty.util.thread.ThreadPoolBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>{@link SelectorManager} manages a number of {@link ManagedSelector}s that
 * simplify the non-blocking primitives provided by the JVM via the {@code java.nio} package.</p>
 * <p>{@link SelectorManager} subclasses implement methods to return protocol-specific
 * {@link EndPoint}s and {@link Connection}s.</p>
 */

@ManagedObject("Manager of the NIO Selectors")
public abstract class SelectorManager extends ContainerLifeCycle implements Dumpable
{
    public static final int DEFAULT_CONNECT_TIMEOUT = 15000;
    protected static final Logger LOG = LoggerFactory.getLogger(SelectorManager.class);

    private final Executor executor;
    private final Scheduler scheduler;
    private final ManagedSelector[] _selectors;
    private final AtomicInteger _selectorIndex = new AtomicInteger();
    private final IntUnaryOperator _selectorIndexUpdate;
    private final List<AcceptListener> _acceptListeners = new CopyOnWriteArrayList<>();
    private long _connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private ThreadPoolBudget.Lease _lease;

    private static int defaultSelectors(Executor executor)
    {
        if (executor instanceof ThreadPool.SizedThreadPool)
        {
            int threads = ((ThreadPool.SizedThreadPool)executor).getMaxThreads();
            int cpus = ProcessorUtils.availableProcessors();
            return Math.max(1, Math.min(cpus / 2, threads / 16));
        }
        return Math.max(1, ProcessorUtils.availableProcessors() / 2);
    }

    protected SelectorManager(Executor executor, Scheduler scheduler)
    {
        this(executor, scheduler, -1);
    }

    /**
     * @param executor The executor to use for handling selected {@link EndPoint}s
     * @param scheduler The scheduler to use for timing events
     * @param selectors The number of selectors to use, or -1 for a default derived
     * from a heuristic over available CPUs and thread pool size.
     */
    protected SelectorManager(Executor executor, Scheduler scheduler, int selectors)
    {
        if (selectors <= 0)
            selectors = defaultSelectors(executor);
        this.executor = executor;
        this.scheduler = scheduler;
        _selectors = new ManagedSelector[selectors];
        _selectorIndexUpdate = index -> (index + 1) % _selectors.length;
    }

    @ManagedAttribute("The Executor")
    public Executor getExecutor()
    {
        return executor;
    }

    @ManagedAttribute("The Scheduler")
    public Scheduler getScheduler()
    {
        return scheduler;
    }

    /**
     * Get the connect timeout
     *
     * @return the connect timeout (in milliseconds)
     */
    @ManagedAttribute("The Connection timeout (ms)")
    public long getConnectTimeout()
    {
        return _connectTimeout;
    }

    /**
     * Set the connect timeout (in milliseconds)
     *
     * @param milliseconds the number of milliseconds for the timeout
     */
    public void setConnectTimeout(long milliseconds)
    {
        _connectTimeout = milliseconds;
    }

    /**
     * Executes the given task in a different thread.
     *
     * @param task the task to execute
     */
    protected void execute(Runnable task)
    {
        executor.execute(task);
    }

    /**
     * @return the number of selectors in use
     */
    @ManagedAttribute("The number of NIO Selectors")
    public int getSelectorCount()
    {
        return _selectors.length;
    }

    protected ManagedSelector chooseSelector()
    {
        return _selectors[_selectorIndex.updateAndGet(_selectorIndexUpdate)];
    }

    /**
     * <p>Registers a channel to perform a non-blocking connect.</p>
     * <p>The channel must be set in non-blocking mode, {@link SocketChannel#connect(SocketAddress)}
     * must be called prior to calling this method, and the connect operation must not be completed
     * (the return value of {@link SocketChannel#connect(SocketAddress)} must be false).</p>
     *
     * @param channel the channel to register
     * @param attachment the attachment object
     * @see #accept(SelectableChannel, Object)
     */
    public void connect(SelectableChannel channel, Object attachment)
    {
        ManagedSelector set = chooseSelector();
        set.submit(set.new Connect(channel, attachment));
    }

    /**
     * @param channel the channel to accept
     * @see #accept(SelectableChannel, Object)
     */
    public void accept(SelectableChannel channel)
    {
        accept(channel, null);
    }

    /**
     * <p>Registers a channel to perform non-blocking read/write operations.</p>
     * <p>This method is called just after a channel has been accepted by {@link ServerSocketChannel#accept()},
     * or just after having performed a blocking connect via {@link Socket#connect(SocketAddress, int)}, or
     * just after a non-blocking connect via {@link SocketChannel#connect(SocketAddress)} that completed
     * successfully.</p>
     *
     * @param channel the channel to register
     * @param attachment the attachment object
     */
    public void accept(SelectableChannel channel, Object attachment)
    {
        ManagedSelector selector = chooseSelector();
        selector.submit(selector.new Accept(channel, attachment));
    }

    /**
     * <p>Registers a server channel for accept operations.
     * When a {@link SocketChannel} is accepted from the given {@link ServerSocketChannel}
     * then the {@link #accepted(SelectableChannel)} method is called, which must be
     * overridden by a derivation of this class to handle the accepted channel
     *
     * @param server the server channel to register
     * @return A Closable that allows the acceptor to be cancelled
     */
    public Closeable acceptor(SelectableChannel server)
    {
        ManagedSelector selector = chooseSelector();
        ManagedSelector.Acceptor acceptor = selector.new Acceptor(server);
        selector.submit(acceptor);
        return acceptor;
    }

    /**
     * Callback method when a channel is accepted from the {@link ServerSocketChannel}
     * passed to {@link #acceptor(SelectableChannel)}.
     * The default impl throws an {@link UnsupportedOperationException}, so it must
     * be overridden by subclasses if a server channel is provided.
     *
     * @param channel the
     * @throws IOException if unable to accept channel
     */
    protected void accepted(SelectableChannel channel) throws IOException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void doStart() throws Exception
    {
        _lease = ThreadPoolBudget.leaseFrom(getExecutor(), this, _selectors.length);
        for (int i = 0; i < _selectors.length; i++)
        {
            ManagedSelector selector = newSelector(i);
            _selectors[i] = selector;
            addBean(selector);
        }
        super.doStart();
    }

    /**
     * <p>Factory method for {@link ManagedSelector}.</p>
     *
     * @param id an identifier for the {@link ManagedSelector to create}
     * @return a new {@link ManagedSelector}
     */
    protected ManagedSelector newSelector(int id)
    {
        return new ManagedSelector(this, id);
    }

    protected Selector newSelector() throws IOException
    {
        return Selector.open();
    }

    @Override
    protected void doStop() throws Exception
    {
        try
        {
            super.doStop();
        }
        finally
        {
            // Cleanup
            for (ManagedSelector selector : _selectors)
            {
                if (selector != null)
                    removeBean(selector);
            }
            Arrays.fill(_selectors, null);
            if (_lease != null)
                _lease.close();
        }
    }

    /**
     * <p>Callback method invoked when an endpoint is opened.</p>
     *
     * @param endpoint the endpoint being opened
     */
    protected void endPointOpened(EndPoint endpoint)
    {
    }

    /**
     * <p>Callback method invoked when an endpoint is closed.</p>
     *
     * @param endpoint the endpoint being closed
     */
    protected void endPointClosed(EndPoint endpoint)
    {
    }

    /**
     * <p>Callback method invoked when a connection is opened.</p>
     *
     * @param connection the connection just opened
     * @param context the attachment associated with the creation of the connection
     * @see #newConnection(SelectableChannel, EndPoint, Object)
     */
    public void connectionOpened(Connection connection, Object context)
    {
        try
        {
            connection.onOpen();
        }
        catch (Throwable x)
        {
            if (isRunning())
                LOG.warn("Exception while notifying connection {}", connection, x);
            else
                LOG.debug("Exception while notifying connection {}", connection, x);
            throw x;
        }
    }

    /**
     * <p>Callback method invoked when a connection is closed.</p>
     *
     * @param connection the connection just closed
     * @param cause the cause of the close or null for normal close
     */
    public void connectionClosed(Connection connection, Throwable cause)
    {
        try
        {
            connection.onClose(cause);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Exception while notifying connection {}", connection, x);
        }
    }

    protected boolean doFinishConnect(SelectableChannel channel) throws IOException
    {
        return ((SocketChannel)channel).finishConnect();
    }

    protected boolean isConnectionPending(SelectableChannel channel)
    {
        return ((SocketChannel)channel).isConnectionPending();
    }

    protected SelectableChannel doAccept(SelectableChannel server) throws IOException
    {
        return ((ServerSocketChannel)server).accept();
    }

    /**
     * <p>Callback method invoked when a non-blocking connect cannot be completed.</p>
     * <p>By default it just logs with level warning.</p>
     *
     * @param channel the channel that attempted the connect
     * @param ex the exception that caused the connect to fail
     * @param attachment the attachment object associated at registration
     */
    protected void connectionFailed(SelectableChannel channel, Throwable ex, Object attachment)
    {
        LOG.warn(String.format("%s - %s", channel, attachment), ex);
    }

    /**
     * <p>Factory method to create {@link EndPoint}.</p>
     * <p>This method is invoked as a result of the registration of a channel via {@link #connect(SelectableChannel, Object)}
     * or {@link #accept(SelectableChannel)}.</p>
     *
     * @param channel the channel associated to the endpoint
     * @param selector the selector the channel is registered to
     * @param selectionKey the selection key
     * @return a new endpoint
     * @throws IOException if the endPoint cannot be created
     * @see #newConnection(SelectableChannel, EndPoint, Object)
     */
    protected abstract EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey selectionKey) throws IOException;

    /**
     * <p>Factory method to create {@link Connection}.</p>
     *
     * @param channel the channel associated to the connection
     * @param endpoint the endpoint
     * @param attachment the attachment
     * @return a new connection
     * @throws IOException if unable to create new connection
     */
    public abstract Connection newConnection(SelectableChannel channel, EndPoint endpoint, Object attachment) throws IOException;

    /**
     * @param listener An EventListener
     * @see AcceptListener
     * @see Container#addEventListener(EventListener)
     */
    @Override
    public boolean addEventListener(EventListener listener)
    {
        if (super.addEventListener(listener))
        {
            if (listener instanceof AcceptListener)
                _acceptListeners.add((AcceptListener)listener);
            return true;
        }
        return false;
    }

    @Override
    public boolean removeEventListener(EventListener listener)
    {
        if (super.removeEventListener(listener))
        {
            if (listener instanceof AcceptListener)
                _acceptListeners.remove(listener);
            return true;
        }
        return false;
    }

    protected void onAccepting(SelectableChannel channel)
    {
        for (AcceptListener l : _acceptListeners)
        {
            try
            {
                l.onAccepting(channel);
            }
            catch (Throwable x)
            {
                LOG.warn("Failed to notify onAccepting on listener {}", l, x);
            }
        }
    }

    protected void onAcceptFailed(SelectableChannel channel, Throwable cause)
    {
        for (AcceptListener l : _acceptListeners)
        {
            try
            {
                l.onAcceptFailed(channel, cause);
            }
            catch (Throwable x)
            {
                LOG.warn("Failed to notify onAcceptFailed on listener {}", l, x);
            }
        }
    }

    protected void onAccepted(SelectableChannel channel)
    {
        for (AcceptListener l : _acceptListeners)
        {
            try
            {
                l.onAccepted(channel);
            }
            catch (Throwable x)
            {
                LOG.warn("Failed to notify onAccepted on listener {}", l, x);
            }
        }
    }

    public interface SelectorManagerListener extends EventListener
    {
    }

    /**
     * <p>A listener for accept events.</p>
     * <p>This listener is called from either the selector or acceptor thread
     * and implementations must be non blocking and fast.</p>
     */
    public interface AcceptListener extends SelectorManagerListener
    {
        /**
         * Called immediately after a new SelectableChannel is accepted, but
         * before it has been submitted to the {@link SelectorManager}.
         *
         * @param channel the accepted channel
         */
        default void onAccepting(SelectableChannel channel)
        {
        }

        /**
         * Called if the processing of the accepted channel fails prior to calling
         * {@link #onAccepted(SelectableChannel)}.
         *
         * @param channel the accepted channel
         * @param cause the cause of the failure
         */
        default void onAcceptFailed(SelectableChannel channel, Throwable cause)
        {
        }

        /**
         * Called after the accepted channel has been allocated an {@link EndPoint}
         * and associated {@link Connection}, and after the onOpen notifications have
         * been called on both endPoint and connection.
         *
         * @param channel the accepted channel
         */
        default void onAccepted(SelectableChannel channel)
        {
        }
    }
}
