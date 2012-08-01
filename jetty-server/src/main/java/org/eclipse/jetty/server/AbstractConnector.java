// ========================================================================
// Copyright (c) 2004-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * Abstract Connector implementation. This abstract implementation of the Connector interface provides:
 * <ul>
 * <li>AbstractLifeCycle implementation</li>
 * <li>Implementations for connector getters and setters</li>
 * <li>Buffer management</li>
 * <li>Socket configuration</li>
 * <li>Base acceptor thread</li>
 * <li>Optional reverse proxy headers checking</li>
 * </ul>
 */
public abstract class AbstractConnector extends AggregateLifeCycle implements Connector, Dumpable
{
    protected final Logger LOG = Log.getLogger(getClass());

    private final Statistics _stats = new ConnectionStatistics();
    private final Thread[] _acceptors;
    private final Executor _executor;
    private final ScheduledExecutorService _scheduler;
    private final Server _server;
    private final ByteBufferPool _byteBufferPool;
    private final boolean _ssl;
    private final SslContextFactory _sslContextFactory;

    /**
     * @deprecated  Make this part of pluggable factory
     */
    private final HttpConfiguration _httpConfig;
    
    private volatile String _name;
    private volatile int _acceptQueueSize = 128;
    private volatile boolean _reuseAddress = true;
    private volatile long _idleTimeout = 200000;
    private volatile int _soLingerTime = -1;

    public AbstractConnector(@Name("server") Server server)
    {
        this(server,null);
    }

    public AbstractConnector(
        @Name("server") Server server,
        @Name("sslContextFactory") SslContextFactory sslContextFactory)
    {
        this(server,null,null,null,sslContextFactory, sslContextFactory!=null, 0);
    }
    
    public AbstractConnector(
        @Name("server") Server server,
        @Name("ssl") boolean ssl)
    {
        this(server,null,null,null,ssl?new SslContextFactory():null, ssl, 0);
    }


    /* ------------------------------------------------------------ */
    /**
     * @param server The server this connector will be added to. Must not be null.
     * @param executor An executor for this connector or null to use the servers executor 
     * @param scheduler A scheduler for this connector or null to use the servers scheduler
     * @param pool A buffer pool for this connector or null to use a default {@link ByteBufferPool}
     * @param sslContextFactory An SslContextFactory to use or null if no ssl is required or to use default {@link SslContextFactory} 
     * @param ssl If true, then new connections will assumed to be SSL. If false, connections can only become SSL if they upgrade and a SslContextFactory is passed.
     * @param acceptors the number of acceptor threads to use, or 0 for a default value.
     */
    public AbstractConnector(
        Server server,
        Executor executor,
        ScheduledExecutorService scheduler,
        ByteBufferPool pool, 
        SslContextFactory sslContextFactory, 
        boolean ssl, 
        int acceptors)
    {
        _server=server;
        _executor=executor!=null?executor:_server.getThreadPool();
        _scheduler=scheduler!=null?scheduler:Executors.newSingleThreadScheduledExecutor(new ThreadFactory()
        {
            @Override
            public Thread newThread(Runnable r)
            {
                return new Thread(r, "Timer-" + getName());
            }
        });
        _byteBufferPool = pool!=null?pool:new StandardByteBufferPool();
        
        _ssl=ssl;
        _sslContextFactory=sslContextFactory!=null?sslContextFactory:(ssl?new SslContextFactory(SslContextFactory.DEFAULT_KEYSTORE_PATH):null);

        addBean(_server,false);
        addBean(_executor,executor==null);
        addBean(_scheduler,scheduler==null);
        addBean(_byteBufferPool,pool==null);
        if (_sslContextFactory!=null)
            addBean(_sslContextFactory,sslContextFactory==null);
        
        if (_sslContextFactory!=null)
        {
            addBean(_sslContextFactory,false);
            setSoLingerTime(30000);
        }
        
        // TODO make this pluggable
        _httpConfig = new HttpConfiguration(_sslContextFactory,ssl);

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

    @Override
    public SslContextFactory getSslContextFactory()
    {
        return _sslContextFactory;
    }

    public HttpConfiguration getHttpConfig()
    {
        return _httpConfig;
    }

    protected AsyncConnection newConnection(AsyncEndPoint endp) throws IOException
    {
        // TODO make this a plugable configurable connection factory for HTTP, HTTPS, SPDY & Websocket
        
        if (_ssl)
        {
            SSLEngine engine = _sslContextFactory.createSSLEngine(endp.getRemoteAddress());
            SslConnection ssl_connection = new SslConnection(getByteBufferPool(), getExecutor(), endp, engine);
            
            AsyncConnection http_connection = new HttpConnection(_httpConfig,this,ssl_connection.getSslEndPoint());
            ssl_connection.getSslEndPoint().setAsyncConnection(http_connection);
            return ssl_connection;
        }
        return new HttpConnection(_httpConfig,this,endp);
    }
    
    /**
     * @return Returns the maxIdleTime.
     */
    @Override
    public long getIdleTimeout()
    {
        return _idleTimeout;
    }

    /**
     * Set the maximum Idle time for a connection, which roughly translates to the {@link Socket#setSoTimeout(int)} call, although with NIO implementations
     * other mechanisms may be used to implement the timeout. The max idle time is applied:
     * <ul>
     * <li>When waiting for a new request to be received on a connection</li>
     * <li>When reading the headers and content of a request</li>
     * <li>When writing the headers and content of a response</li>
     * </ul>
     * Jetty interprets this value as the maximum time between some progress being made on the connection. So if a single byte is read or written, then the
     * timeout (if implemented by jetty) is reset. However, in many instances, the reading/writing is delegated to the JVM, and the semantic is more strictly
     * enforced as the maximum time a single read/write operation can take. Note, that as Jetty supports writes of memory mapped file buffers, then a write may
     * take many 10s of seconds for large content written to a slow device.
     * <p/>
     * Previously, Jetty supported separate idle timeouts and IO operation timeouts, however the expense of changing the value of soTimeout was significant, so
     * these timeouts were merged. With the advent of NIO, it may be possible to again differentiate these values (if there is demand).
     *
     * @param idleTimeout The idleTimeout to set.
     */
    public void setIdleTimeout(long idleTimeout)
    {
        _idleTimeout = idleTimeout;
    }

    /**
     * @return Returns the soLingerTime.
     */
    public int getSoLingerTime()
    {
        return _soLingerTime;
    }

    /**
     * @return Returns the acceptQueueSize.
     */
    public int getAcceptQueueSize()
    {
        return _acceptQueueSize;
    }

    /**
     * @param acceptQueueSize The acceptQueueSize to set.
     */
    public void setAcceptQueueSize(int acceptQueueSize)
    {
        _acceptQueueSize = acceptQueueSize;
    }

    /**
     * @return Returns the number of acceptor threads.
     */
    public int getAcceptors()
    {
        return _acceptors.length;
    }


    /**
     * @param soLingerTime The soLingerTime to set or -1 to disable.
     */
    public void setSoLingerTime(int soLingerTime)
    {
        _soLingerTime = soLingerTime;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();

        // Start selector thread
        synchronized (this)
        {
            for (int i = 0; i < _acceptors.length; i++)
                getExecutor().execute(new Acceptor(i));
        }

        LOG.info("Started {}", this);
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();

        for (Thread thread : _acceptors)
        {
            if (thread != null)
                thread.interrupt();
        }
    }

    public void join() throws InterruptedException
    {
        for (Thread thread : _acceptors)
            if (thread != null)
                thread.join();
    }

    protected void configure(Socket socket)
    {
        try
        {
            socket.setTcpNoDelay(true);
            if (_soLingerTime >= 0)
                socket.setSoLinger(true, _soLingerTime / 1000);
            else
                socket.setSoLinger(false, 0);
        }
        catch (Exception e)
        {
            LOG.ignore(e);
        }
    }

    protected abstract void accept(int acceptorID) throws IOException, InterruptedException;

    /* ------------------------------------------------------------ */
    private class Acceptor implements Runnable
    {
        int _acceptor = 0;

        Acceptor(int id)
        {
            _acceptor = id;
        }

        @Override
        public void run()
        {
            Thread current = Thread.currentThread();
            String name;
            synchronized (AbstractConnector.this)
            {
                if (!isRunning())
                    return;

                _acceptors[_acceptor] = current;
                name = _acceptors[_acceptor].getName();
                current.setName(name + " Acceptor" + _acceptor + " " + AbstractConnector.this);
            }
            int old_priority = current.getPriority();

            try
            {
                current.setPriority(old_priority);
                while (isRunning() && getTransport() != null)
                {
                    try
                    {
                        accept(_acceptor);
                    }
                    catch (IOException | InterruptedException e)
                    {
                        LOG.ignore(e);
                    }
                    catch (Throwable e)
                    {
                        LOG.warn(e);
                    }
                }
            }
            finally
            {
                current.setPriority(old_priority);
                current.setName(name);

                synchronized (AbstractConnector.this)
                {
                    _acceptors[_acceptor] = null;
                }
            }
        }
    }

    @Override
    public String getName()
    {
        return _name;
    }

    public void setName(String name)
    {
        _name = name;
    }

    protected void connectionOpened(AsyncConnection connection)
    {
        _stats.connectionOpened();
    }

    protected void connectionUpgraded(AsyncConnection oldConnection, AsyncConnection newConnection)
    {
        long duration = System.currentTimeMillis() - oldConnection.getEndPoint().getCreatedTimeStamp();
        int requests = (oldConnection instanceof HttpConnection) ? ((HttpConnection)oldConnection).getHttpChannel().getRequests() : 0;
        _stats.connectionUpgraded(duration, requests, requests);
    }

    protected void connectionClosed(AsyncConnection connection)
    {
        long duration = System.currentTimeMillis() - connection.getEndPoint().getCreatedTimeStamp();
        // TODO: remove casts to HttpConnection
        int requests = (connection instanceof HttpConnection) ? ((HttpConnection)connection).getHttpChannel().getRequests() : 0;
        _stats.connectionClosed(duration, requests, requests);
    }

    /**
     * @return True if the the server socket will be opened in SO_REUSEADDR mode.
     */
    public boolean getReuseAddress()
    {
        return _reuseAddress;
    }

    /**
     * @param reuseAddress True if the the server socket will be opened in SO_REUSEADDR mode.
     */
    public void setReuseAddress(boolean reuseAddress)
    {
        _reuseAddress = reuseAddress;
    }

    public ScheduledExecutorService getScheduler()
    {
        return _scheduler;
    }
}
