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
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ReservedThreadExecutor;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.eclipse.jetty.util.thread.ThreadPoolBudget;
import org.eclipse.jetty.util.thread.strategy.EatWhatYouKill;

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
    protected static final Logger LOG = Log.getLogger(SelectorManager.class);

    private final Executor executor;
    private final Scheduler scheduler;
    private final ManagedSelector[] _selectors;
    private final AtomicInteger _selectorIndex = new AtomicInteger();
    private final IntUnaryOperator _selectorIndexUpdate;
    private long _connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private int _reservedThreads = -1;
    private ThreadPoolBudget.Lease _lease;

    private static int defaultSelectors(Executor executor)
    {
        if (executor instanceof ThreadPool.SizedThreadPool)
        {
            int threads = ((ThreadPool.SizedThreadPool)executor).getMaxThreads();
            int cpus = Runtime.getRuntime().availableProcessors();
            return Math.max(1,Math.min(cpus/2,threads/16));
        }
        return Math.max(1,Runtime.getRuntime().availableProcessors()/2);
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
        _selectorIndexUpdate = index -> (index+1)%_selectors.length;
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
     * Get the number of preallocated producing threads
     * @see EatWhatYouKill
     * @see ReservedThreadExecutor
     * @return The number of threads preallocated to producing (default -1).
     */
    @ManagedAttribute("The number of reserved producer threads")
    public int getReservedThreads()
    {
        return _reservedThreads;
    }
    
    /**
     * Set the number of reserved threads for high priority tasks.
     * <p>Reserved threads are used to take over producing duties, so that a 
     * producer thread may immediately consume a task it has produced (EatWhatYouKill
     * scheduling). If a reserved thread is not available, then produced tasks must
     * be submitted to an executor to be executed by a different thread.
     * @see EatWhatYouKill
     * @see ReservedThreadExecutor
     * @param threads  The number of producing threads to preallocate. If 
     * less that 0 (the default), then a heuristic based on the number of CPUs and
     * the thread pool size is used to select the number of threads. If 0, no 
     * threads are preallocated and the EatWhatYouKill scheduler will be 
     * disabled and all produced tasks will be executed in a separate thread. 
     */
    public void setReservedThreads(int threads)
    {
        _reservedThreads = threads;
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

    private ManagedSelector chooseSelector(SelectableChannel channel)
    {
        return _selectors[_selectorIndex.updateAndGet(_selectorIndexUpdate)];
    }

    /**
     * <p>Registers a channel to perform a non-blocking connect.</p>
     * <p>The channel must be set in non-blocking mode, {@link SocketChannel#connect(SocketAddress)}
     * must be called prior to calling this method, and the connect operation must not be completed
     * (the return value of {@link SocketChannel#connect(SocketAddress)} must be false).</p>
     *
     * @param channel    the channel to register
     * @param attachment the attachment object
     * @see #accept(SelectableChannel, Object)
     */
    public void connect(SelectableChannel channel, Object attachment)
    {
        ManagedSelector set = chooseSelector(channel);
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
     * @param channel    the channel to register
     * @param attachment the attachment object
     */
    public void accept(SelectableChannel channel, Object attachment)
    {
        final ManagedSelector selector = chooseSelector(channel);
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
        final ManagedSelector selector = chooseSelector(null);
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
        addBean(new ReservedThreadExecutor(getExecutor(),_reservedThreads,this),true);
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

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        for (ManagedSelector selector : _selectors)
            removeBean(selector);
        if (_lease != null)
            _lease.close();
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
     */
    public void connectionOpened(Connection connection)
    {
        try
        {
            connection.onOpen();
        }
        catch (Throwable x)
        {
            if (isRunning())
                LOG.warn("Exception while notifying connection " + connection, x);
            else
                LOG.debug("Exception while notifying connection " + connection, x);
            throw x;
        }
    }

    /**
     * <p>Callback method invoked when a connection is closed.</p>
     *
     * @param connection the connection just closed
     */
    public void connectionClosed(Connection connection)
    {
        try
        {
            connection.onClose();
        }
        catch (Throwable x)
        {
            LOG.debug("Exception while notifying connection " + connection, x);
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
     * @param channel    the channel that attempted the connect
     * @param ex         the exception that caused the connect to fail
     * @param attachment the attachment object associated at registration
     */
    protected void connectionFailed(SelectableChannel channel, Throwable ex, Object attachment)
    {
        LOG.warn(String.format("%s - %s", channel, attachment), ex);
    }

    protected Selector newSelector() throws IOException
    {
        return Selector.open();
    }

    /**
     * <p>Factory method to create {@link EndPoint}.</p>
     * <p>This method is invoked as a result of the registration of a channel via {@link #connect(SelectableChannel, Object)}
     * or {@link #accept(SelectableChannel)}.</p>
     *
     * @param channel      the channel associated to the endpoint
     * @param selector     the selector the channel is registered to
     * @param selectionKey the selection key
     * @return a new endpoint
     * @throws IOException if the endPoint cannot be created
     * @see #newConnection(SelectableChannel, EndPoint, Object)
     */
    protected abstract EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey selectionKey) throws IOException;

    /**
     * <p>Factory method to create {@link Connection}.</p>
     *
     * @param channel    the channel associated to the connection
     * @param endpoint   the endpoint
     * @param attachment the attachment
     * @return a new connection
     * @throws IOException if unable to create new connection
     */
    public abstract Connection newConnection(SelectableChannel channel, EndPoint endpoint, Object attachment) throws IOException;


}
