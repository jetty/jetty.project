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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * <p>An abstract implementation of {@link Connector} that provides a {@link ConnectionFactory} mechanism
 * for creating {@link Connection} instances for various protocols (HTTP, SSL, SPDY, etc).</p>
 *
 * <h2>Connector Services</h2>
 * The abstract connector manages the dependent services needed by all specific connector instances:
 * <ul>
 * <li>The {@link Executor} service is used to run all active tasks needed by this connector such as accepting connections
 * or handle HTTP requests. The default is to use the {@link Server#getThreadPool()} as an executor.
 * </li>
 * <li>The {@link Scheduler} service is used to monitor the idle timeouts of all connections and is also made available
 * to the connections to time such things as asynchronous request timeouts.  The default is to use a new
 * {@link ScheduledExecutorScheduler} instance.
 * </li>
 * <li>The {@link ByteBufferPool} service is made available to all connections to be used to acquire and release
 * {@link ByteBuffer} instances from a pool.  The default is to use a new {@link ArrayByteBufferPool} instance.
 * </li>
 * </ul>
 * These services are managed as aggregate beans by the {@link ContainerLifeCycle} super class and
 * may either be managed or unmanaged beans.
 *
 * <h2>Connection Factories</h2>
 * The connector keeps a collection of {@link ConnectionFactory} instances, each of which are known by their
 * protocol name.  The protocol name may be a real protocol (eg http/1.1 or spdy/3) or it may be a private name
 * that represents a special connection factory. For example, the name "SSL-http/1.1" is used for
 * an {@link SslConnectionFactory} that has been instantiated with the {@link HttpConnectionFactory} as it's
 * next protocol.
 *
 * <h4>Configuring Connection Factories</h4>
 * The collection of available {@link ConnectionFactory} may be constructor injected or modified with the
 * methods {@link #addConnectionFactory(ConnectionFactory)}, {@link #removeConnectionFactory(String)} and
 * {@link #setConnectionFactories(Collection)}.  Only a single {@link ConnectionFactory} instance may be configured
 * per protocol name, so if two factories with the same {@link ConnectionFactory#getProtocol()} are set, then
 * the second will replace the first.
 * <p>
 * The protocol factory used for newly accepted connections is specified by
 * the method {@link #setDefaultProtocol(String)} or defaults to the protocol of the first configured factory.
 * <p>
 * Each Connection factory type is responsible for the configuration of the protocols that it accepts. Thus to
 * configure the HTTP protocol, you pass a {@link HttpConfiguration} instance to the {@link HttpConnectionFactory}
 * (or the SPDY factories that can also provide HTTP Semantics).  Similarly the {@link SslConnectionFactory} is
 * configured by passing it a {@link SslContextFactory} and a next protocol name.
 *
 * <h4>Connection Factory Operation</h4>
 * {@link ConnectionFactory}s may simply create a {@link Connection} instance to support a specific
 * protocol.  For example, the {@link HttpConnectionFactory} will create a {@link HttpConnection} instance
 * that can handle http/1.1, http/1.0 and http/0.9.
 * <p>
 * {@link ConnectionFactory}s may also create a chain of {@link Connection} instances, using other {@link ConnectionFactory} instances.
 * For example, the {@link SslConnectionFactory} is configured with a next protocol name, so that once it has accepted
 * a connection and created an {@link SslConnection}, it then used the next {@link ConnectionFactory} from the
 * connector using the {@link #getConnectionFactory(String)} method, to create a {@link Connection} instance that
 * will handle the unecrypted bytes from the {@link SslConnection}.   If the next protocol is "http/1.1", then the
 * {@link SslConnectionFactory} will have a protocol name of "SSL-http/1.1" and lookup "http/1.1" for the protocol
 * to run over the SSL connection.
 * <p>
 * {@link ConnectionFactory}s may also create temporary {@link Connection} instances that will exchange bytes
 * over the connection to determine what is the next protocol to use.  For example the NPN protocol is an extension
 * of SSL to allow a protocol to be specified during the SSL handshake. NPN is used by the SPDY protocol to
 * negotiate the version of SPDY or HTTP that the client and server will speak.  Thus to accept a SPDY connection, the
 * connector will be configured with {@link ConnectionFactory}s for "SSL-NPN", "NPN", "spdy/3", "spdy/2", "http/1.1"
 * with the default protocol being "SSL-NPN".  Thus a newly accepted connection uses "SSL-NPN", which specifies a
 * SSLConnectionFactory with "NPN" as the next protocol.  Thus an SslConnection instance is created chained to an NPNConnection
 * instance.  The NPN connection then negotiates with the client to determined the next protocol, which could be
 * "spdy/3", "spdy/2" or the default of "http/1.1".  Once the next protocol is determined, the NPN connection
 * calls {@link #getConnectionFactory(String)} to create a connection instance that will replace the NPN connection as
 * the connection chained to the SSLConnection.
 * <p>
 * <h2>Acceptors</h2>
 * The connector will execute a number of acceptor tasks to the {@link Exception} service passed to the constructor.
 * The acceptor tasks run in a loop while the connector is running and repeatedly call the abstract {@link #accept(int)} method.
 * The implementation of the accept method must:
 * <nl>
 * <li>block waiting for new connections
 * <li>accept the connection (eg socket accept)
 * <li>perform any configuration of the connection (eg. socket linger times)
 * <li>call the {@link #getDefaultConnectionFactory()} {@link ConnectionFactory#newConnection(Connector, org.eclipse.jetty.io.EndPoint)}
 * method to create a new Connection instance.
 * </nl>
 * The default number of acceptor tasks is the minimum of 1 and half the number of available CPUs. Having more acceptors may reduce
 * the latency for servers that see a high rate of new connections (eg HTTP/1.0 without keep-alive).  Typically the default is
 * sufficient for modern persistent protocols (HTTP/1.1, SPDY etc.)
 */
@ManagedObject("Abstract implementation of the Connector Interface")
public abstract class AbstractConnector extends ContainerLifeCycle implements Connector, Dumpable
{
    protected final Logger LOG = Log.getLogger(getClass());
    // Order is important on server side, so we use a LinkedHashMap
    private final Map<String, ConnectionFactory> _factories = new LinkedHashMap<>();
    private final Server _server;
    private final Executor _executor;
    private final Scheduler _scheduler;
    private final ByteBufferPool _byteBufferPool;
    private final Thread[] _acceptors;
    private final Set<EndPoint> _endpoints = Collections.newSetFromMap(new ConcurrentHashMap<EndPoint, Boolean>());
    private final Set<EndPoint> _immutableEndPoints = Collections.unmodifiableSet(_endpoints);
    private volatile CountDownLatch _stopping;
    private long _idleTimeout = 30000;
    private String _defaultProtocol;
    private ConnectionFactory _defaultConnectionFactory;
    private String _name;


    /**
     * @param server The server this connector will be added to. Must not be null.
     * @param executor An executor for this connector or null to use the servers executor
     * @param scheduler A scheduler for this connector or null to either a {@link Scheduler} set as a server bean or if none set, then a new {@link ScheduledExecutorScheduler} instance.
     * @param pool A buffer pool for this connector or null to either a {@link ByteBufferPool} set as a server bean or none set, the new  {@link ArrayByteBufferPool} instance.
     * @param acceptors the number of acceptor threads to use, or 0 for a default value.
     * @param factories The Connection Factories to use.
     */
    public AbstractConnector(
            Server server,
            Executor executor,
            Scheduler scheduler,
            ByteBufferPool pool,
            int acceptors,
            ConnectionFactory... factories)
    {
        _server=server;
        _executor=executor!=null?executor:_server.getThreadPool();
        if (scheduler==null)
            scheduler=_server.getBean(Scheduler.class);
        _scheduler=scheduler!=null?scheduler:new ScheduledExecutorScheduler();
        if (pool==null)
            pool=_server.getBean(ByteBufferPool.class);
        _byteBufferPool = pool!=null?pool:new ArrayByteBufferPool();

        addBean(_server,false);
        addBean(_executor);
        if (executor==null)
            unmanage(_executor); // inherited from server
        addBean(_scheduler);
        addBean(_byteBufferPool);

        for (ConnectionFactory factory:factories)
            addConnectionFactory(factory);

        int cores = Runtime.getRuntime().availableProcessors();
        if (acceptors < 0)
            acceptors = 1 + cores / 16;
        if (acceptors > 2 * cores)
            LOG.warn("Acceptors should be <= 2*availableProcessors: " + this);
        _acceptors = new Thread[acceptors];
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
    @ManagedAttribute("Idle timeout")
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
        _defaultConnectionFactory = getConnectionFactory(_defaultProtocol);
        if(_defaultConnectionFactory==null)
            throw new IllegalStateException("No protocol factory for default protocol: "+_defaultProtocol);

        super.doStart();

        _stopping=new CountDownLatch(_acceptors.length);
        for (int i = 0; i < _acceptors.length; i++)
            getExecutor().execute(new Acceptor(i));

        LOG.info("Started {}", this);
    }


    protected void interruptAcceptors()
    {
        synchronized (this)
        {
            for (Thread thread : _acceptors)
            {
                if (thread != null)
                    thread.interrupt();
            }
        }
    }

    @Override
    public Future<Void> shutdown()
    {
        return new FutureCallback(true);
    }

    @Override
    protected void doStop() throws Exception
    {
        // Tell the acceptors we are stopping
        interruptAcceptors();

        // If we have a stop timeout
        long stopTimeout = getStopTimeout();
        CountDownLatch stopping=_stopping;
        if (stopTimeout > 0 && stopping!=null)
            stopping.await(stopTimeout,TimeUnit.MILLISECONDS);
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
        synchronized (this)
        {
            for (Thread thread : _acceptors)
                if (thread != null)
                    thread.join(timeout);
        }
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

    @Override
    public ConnectionFactory getConnectionFactory(String protocol)
    {
        synchronized (_factories)
        {
            return _factories.get(protocol.toLowerCase(Locale.ENGLISH));
        }
    }

    @Override
    public <T> T getConnectionFactory(Class<T> factoryType)
    {
        synchronized (_factories)
        {
            for (ConnectionFactory f : _factories.values())
                if (factoryType.isAssignableFrom(f.getClass()))
                    return (T)f;
            return null;
        }
    }

    public void addConnectionFactory(ConnectionFactory factory)
    {
        synchronized (_factories)
        {
            ConnectionFactory old=_factories.remove(factory.getProtocol());
            if (old!=null)
                removeBean(old);
            _factories.put(factory.getProtocol().toLowerCase(Locale.ENGLISH), factory);
            addBean(factory);
            if (_defaultProtocol==null)
                _defaultProtocol=factory.getProtocol();
        }
    }

    public ConnectionFactory removeConnectionFactory(String protocol)
    {
        synchronized (_factories)
        {
            ConnectionFactory factory= _factories.remove(protocol.toLowerCase(Locale.ENGLISH));
            removeBean(factory);
            return factory;
        }
    }

    @Override
    public Collection<ConnectionFactory> getConnectionFactories()
    {
        synchronized (_factories)
        {
            return _factories.values();
        }
    }

    public void setConnectionFactories(Collection<ConnectionFactory> factories)
    {
        synchronized (_factories)
        {
            List<ConnectionFactory> existing = new ArrayList<>(_factories.values());
            for (ConnectionFactory factory: existing)
                removeConnectionFactory(factory.getProtocol());
            for (ConnectionFactory factory: factories)
                if (factory!=null)
                    addConnectionFactory(factory);
        }
    }


    @Override
    @ManagedAttribute("Protocols supported by this connector")
    public List<String> getProtocols()
    {
        synchronized (_factories)
        {
            return new ArrayList<>(_factories.keySet());
        }
    }

    public void clearConnectionFactories()
    {
        synchronized (_factories)
        {
            _factories.clear();
        }
    }

    @ManagedAttribute("This connector's default protocol")
    public String getDefaultProtocol()
    {
        return _defaultProtocol;
    }

    public void setDefaultProtocol(String defaultProtocol)
    {
        _defaultProtocol = defaultProtocol.toLowerCase(Locale.ENGLISH);
        if (isRunning())
            _defaultConnectionFactory=getConnectionFactory(_defaultProtocol);
    }

    @Override
    public ConnectionFactory getDefaultConnectionFactory()
    {
        if (isStarted())
            return _defaultConnectionFactory;
        return getConnectionFactory(_defaultProtocol);
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
                            LOG.ignore(e);
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
                CountDownLatch stopping=_stopping;
                if (stopping!=null)
                    stopping.countDown();
            }
        }
    }




//    protected void connectionOpened(Connection connection)
//    {
//        _stats.connectionOpened();
//        connection.onOpen();
//    }
//
//    protected void connectionClosed(Connection connection)
//    {
//        connection.onClose();
//        long duration = System.currentTimeMillis() - connection.getEndPoint().getCreatedTimeStamp();
//        _stats.connectionClosed(duration, connection.getMessagesIn(), connection.getMessagesOut());
//    }
//
//    public void connectionUpgraded(Connection oldConnection, Connection newConnection)
//    {
//        oldConnection.onClose();
//        _stats.connectionUpgraded(oldConnection.getMessagesIn(), oldConnection.getMessagesOut());
//        newConnection.onOpen();
//    }

    @Override
    public Collection<EndPoint> getConnectedEndPoints()
    {
        return _immutableEndPoints;
    }

    protected void onEndPointOpened(EndPoint endp)
    {
        _endpoints.add(endp);
    }

    protected void onEndPointClosed(EndPoint endp)
    {
        _endpoints.remove(endp);
    }

    @Override
    public Scheduler getScheduler()
    {
        return _scheduler;
    }

    @Override
    public String getName()
    {
        return _name;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Set a connector name.   A context may be configured with
     * virtual hosts in the form "@contextname" and will only serve
     * requests from the named connector,
     * @param name A connector name.
     */
    public void setName(String name)
    {
        _name=name;
    }
    
    @Override
    public String toString()
    {
        return String.format("%s@%x{%s}",
                _name==null?getClass().getSimpleName():_name,
                hashCode(),
                getDefaultProtocol());
    }
}
