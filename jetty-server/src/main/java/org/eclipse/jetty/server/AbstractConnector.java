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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.SimpleScheduler;

/**
 * <p>Partial implementation of {@link Connector}</p>
 */
@ManagedObject("Abstract implementation of the Connector Interface")
public abstract class AbstractConnector extends AggregateLifeCycle implements Connector, Dumpable
{
    protected final Logger LOG = Log.getLogger(getClass());
    // Order is important on server side, so we use a LinkedHashMap
    private final Map<String, ConnectionFactory> factories = new LinkedHashMap<>();
    private final Statistics _stats = new ConnectorStatistics();
    private final Server _server;
    private final SslContextFactory _sslContextFactory;
    private final Executor _executor;
    private final Scheduler _scheduler;
    private final ByteBufferPool _byteBufferPool;
    private final Thread[] _acceptors;
    private volatile CountDownLatch _stopping;
    private volatile long _idleTimeout = 200000;
    private volatile ConnectionFactory defaultConnectionFactory;

    /**
     * @param server The server this connector will be added to. Must not be null.
     * @param executor An executor for this connector or null to use the servers executor
     * @param scheduler A scheduler for this connector or null to use the servers scheduler
     * @param pool A buffer pool for this connector or null to use a default {@link ByteBufferPool}
     * @param sslContextFactory the SSL context factory to make this connector SSL enabled, or null
     * @param acceptors the number of acceptor threads to use, or 0 for a default value.
     */
    public AbstractConnector(
            Server server,
            Executor executor,
            Scheduler scheduler,
            ByteBufferPool pool,
            SslContextFactory sslContextFactory,
            int acceptors)
    {
        _server=server;
        _executor=executor!=null?executor:_server.getThreadPool();
        _scheduler=scheduler!=null?scheduler:new SimpleScheduler();
        _byteBufferPool = pool!=null?pool:new MappedByteBufferPool();
        _sslContextFactory = sslContextFactory;

        addBean(_server,false);
        addBean(_executor);
        if (executor==null)
            unmanage(_executor); // inherited from server
        addBean(_scheduler);
        addBean(_byteBufferPool);
        addBean(_sslContextFactory);
        addBean(_stats,true);

        if (acceptors<=0)
            acceptors=Math.max(1,(Runtime.getRuntime().availableProcessors()) / 4);
        if (acceptors > 2 * Runtime.getRuntime().availableProcessors())
            LOG.warn("Acceptors should be <= 2*availableProcessors: " + this);
        _acceptors = new Thread[acceptors];
    }

    @Override
    public Statistics getStatistics()
    {
        return _stats;
    }

    @Override
    public Server getServer()
    {
        return _server;
    }

    @Override
    public Executor getExecutor()
    {
        return _executor;
    }

    @Override
    public ByteBufferPool getByteBufferPool()
    {
        return _byteBufferPool;
    }

    public SslContextFactory getSslContextFactory()
    {
        return _sslContextFactory;
    }

    @Override
    public long getIdleTimeout()
    {
        return _idleTimeout;
    }

    /**
     * <p>Sets the maximum Idle time for a connection, which roughly translates to the {@link Socket#setSoTimeout(int)}
     * call, although with NIO implementations other mechanisms may be used to implement the timeout.</p>
     * <p>The max idle time is applied:</p>
     * <ul>
     * <li>When waiting for a new message to be received on a connection</li>
     * <li>When waiting for a new message to be sent on a connection</li>
     * </ul>
     * <p>This value is interpreted as the maximum time between some progress being made on the connection.
     * So if a single byte is read or written, then the timeout is reset.</p>
     *
     * @param idleTimeout the idle timeout
     */
    public void setIdleTimeout(long idleTimeout)
    {
        _idleTimeout = idleTimeout;
    }

    /**
     * @return Returns the number of acceptor threads.
     */
    @ManagedAttribute("number of acceptor threads")
    public int getAcceptors()
    {
        return _acceptors.length;
    }


    @Override
    protected void doStart() throws Exception
    {
        super.doStart();

        _stopping=new CountDownLatch(_acceptors.length);
        for (int i = 0; i < _acceptors.length; i++)
            getExecutor().execute(new Acceptor(i));

        LOG.info("Started {}", this);
    }


    protected void interruptAcceptors()
    {
        for (Thread thread : _acceptors)
        {
            if (thread != null)
                thread.interrupt();
        }
    }

    @Override
    public <C> Future<C> shutdown(C c)
    {
        return new FutureCallback<C>(c);
    }

    @Override
    protected void doStop() throws Exception
    {
        // Tell the acceptors we are stopping
        interruptAcceptors();

        // If we have a stop timeout
        long stopTimeout = getStopTimeout();
        if (stopTimeout > 0 && _stopping!=null)
            _stopping.await(stopTimeout,TimeUnit.MILLISECONDS);
        _stopping=null;

        super.doStop();

        LOG.info("Stopped {}", this);
    }

    public void join() throws InterruptedException
    {
        join(0);
    }

    public void join(long timeout) throws InterruptedException
    {
        for (Thread thread : _acceptors)
            if (thread != null)
                thread.join(timeout);
    }

    protected abstract void accept(int acceptorID) throws IOException, InterruptedException;


    /* ------------------------------------------------------------ */
    /**
     * @return Is the connector accepting new connections
     */
    protected boolean isAccepting()
    {
        return isRunning();
    }

    public ConnectionFactory getConnectionFactory(String protocol)
    {
        synchronized (factories)
        {
            return factories.get(protocol);
        }
    }

    public ConnectionFactory putConnectionFactory(String protocol, ConnectionFactory factory)
    {
        synchronized (factories)
        {
            return factories.put(protocol, factory);
        }
    }

    public ConnectionFactory removeConnectionFactory(String protocol)
    {
        synchronized (factories)
        {
            return factories.remove(protocol);
        }
    }

    public Map<String, ConnectionFactory> getConnectionFactories()
    {
        synchronized (factories)
        {
            return new LinkedHashMap<>(factories);
        }
    }

    public void clearConnectionFactories()
    {
        synchronized (factories)
        {
            factories.clear();
        }
    }

    public ConnectionFactory getDefaultConnectionFactory()
    {
        return defaultConnectionFactory;
    }

    public void setDefaultConnectionFactory(ConnectionFactory defaultConnectionFactory)
    {
        this.defaultConnectionFactory = defaultConnectionFactory;
    }

    private class Acceptor implements Runnable
    {
        private final int _acceptor;

        private Acceptor(int id)
        {
            _acceptor = id;
        }

        @Override
        public void run()
        {
            Thread current = Thread.currentThread();
            String name = current.getName();
            current.setName(name + "-acceptor-" + _acceptor + "-" + AbstractConnector.this);

            synchronized (AbstractConnector.this)
            {
                _acceptors[_acceptor] = current;
            }

            try
            {
                while (isAccepting())
                {
                    try
                    {
                        accept(_acceptor);
                    }
                    catch (Throwable e)
                    {
                        if (isAccepting())
                            LOG.warn(e);
                        else
                            LOG.debug(e);
                    }
                }
            }
            finally
            {
                current.setName(name);

                synchronized (AbstractConnector.this)
                {
                    _acceptors[_acceptor] = null;
                }
                _stopping.countDown();
            }
        }
    }

    protected void connectionOpened(Connection connection)
    {
        _stats.connectionOpened();
    }

    protected void connectionUpgraded(Connection oldConnection, Connection newConnection)
    {
        long duration = System.currentTimeMillis() - oldConnection.getEndPoint().getCreatedTimeStamp();
        int requests = (oldConnection instanceof HttpConnection) ? ((HttpConnection)oldConnection).getHttpChannel().getRequests() : 0;
        _stats.connectionUpgraded(duration, requests, requests);
    }

    protected void connectionClosed(Connection connection)
    {
        long duration = System.currentTimeMillis() - connection.getEndPoint().getCreatedTimeStamp();
        // TODO: remove casts to HttpConnection
        int requests = (connection instanceof HttpConnection) ? ((HttpConnection)connection).getHttpChannel().getRequests() : 0;
        _stats.connectionClosed(duration, requests, requests);
    }

    @Override
    public Scheduler getScheduler()
    {
        return _scheduler;
    }
}
