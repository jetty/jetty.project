//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.stream.Collectors;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.Graceful;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Locker;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.ThreadPoolBudget;

/**
 * <p>An abstract implementation of {@link Connector} that provides a {@link ConnectionFactory} mechanism
 * for creating {@link org.eclipse.jetty.io.Connection} instances for various protocols (HTTP, SSL, etc).</p>
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
 * protocol name.  The protocol name may be a real protocol (e.g. "http/1.1" or "h2") or it may be a private name
 * that represents a special connection factory. For example, the name "SSL-http/1.1" is used for
 * an {@link SslConnectionFactory} that has been instantiated with the {@link HttpConnectionFactory} as it's
 * next protocol.
 *
 * <h2>Configuring Connection Factories</h2>
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
 * (or other factories that can also provide HTTP Semantics).  Similarly the {@link SslConnectionFactory} is
 * configured by passing it a {@link SslContextFactory} and a next protocol name.
 *
 * <h2>Connection Factory Operation</h2>
 * {@link ConnectionFactory}s may simply create a {@link org.eclipse.jetty.io.Connection} instance to support a specific
 * protocol.  For example, the {@link HttpConnectionFactory} will create a {@link HttpConnection} instance
 * that can handle http/1.1, http/1.0 and http/0.9.
 * <p>
 * {@link ConnectionFactory}s may also create a chain of {@link org.eclipse.jetty.io.Connection} instances, using other {@link ConnectionFactory} instances.
 * For example, the {@link SslConnectionFactory} is configured with a next protocol name, so that once it has accepted
 * a connection and created an {@link SslConnection}, it then used the next {@link ConnectionFactory} from the
 * connector using the {@link #getConnectionFactory(String)} method, to create a {@link org.eclipse.jetty.io.Connection} instance that
 * will handle the unencrypted bytes from the {@link SslConnection}.   If the next protocol is "http/1.1", then the
 * {@link SslConnectionFactory} will have a protocol name of "SSL-http/1.1" and lookup "http/1.1" for the protocol
 * to run over the SSL connection.
 * <p>
 * {@link ConnectionFactory}s may also create temporary {@link org.eclipse.jetty.io.Connection} instances that will exchange bytes
 * over the connection to determine what is the next protocol to use.  For example the ALPN protocol is an extension
 * of SSL to allow a protocol to be specified during the SSL handshake. ALPN is used by the HTTP/2 protocol to
 * negotiate the protocol that the client and server will speak.  Thus to accept an HTTP/2 connection, the
 * connector will be configured with {@link ConnectionFactory}s for "SSL-ALPN", "h2", "http/1.1"
 * with the default protocol being "SSL-ALPN".  Thus a newly accepted connection uses "SSL-ALPN", which specifies a
 * SSLConnectionFactory with "ALPN" as the next protocol.  Thus an SSL connection instance is created chained to an ALPN
 * connection instance.  The ALPN connection then negotiates with the client to determined the next protocol, which
 * could be "h2" or the default of "http/1.1".  Once the next protocol is determined, the ALPN connection
 * calls {@link #getConnectionFactory(String)} to create a connection instance that will replace the ALPN connection as
 * the connection chained to the SSL connection.
 * <h2>Acceptors</h2>
 * The connector will execute a number of acceptor tasks to the {@link Exception} service passed to the constructor.
 * The acceptor tasks run in a loop while the connector is running and repeatedly call the abstract {@link #accept(int)} method.
 * The implementation of the accept method must:
 * <ol>
 * <li>block waiting for new connections</li>
 * <li>accept the connection (eg socket accept)</li>
 * <li>perform any configuration of the connection (eg. socket configuration)</li>
 * <li>call the {@link #getDefaultConnectionFactory()} {@link ConnectionFactory#newConnection(Connector, org.eclipse.jetty.io.EndPoint)}
 * method to create a new Connection instance.</li>
 * </ol>
 * The default number of acceptor tasks is the minimum of 1 and the number of available CPUs divided by 8. Having more acceptors may reduce
 * the latency for servers that see a high rate of new connections (eg HTTP/1.0 without keep-alive).  Typically the default is
 * sufficient for modern persistent protocols (HTTP/1.1, HTTP/2 etc.)
 */
@ManagedObject("Abstract implementation of the Connector Interface")
public abstract class AbstractConnector extends ContainerLifeCycle implements Connector, Dumpable
{
    protected static final Logger LOG = Log.getLogger(AbstractConnector.class);

    private final Locker _locker = new Locker();
    private final Condition _setAccepting = _locker.newCondition();
    private final Map<String, ConnectionFactory> _factories = new LinkedHashMap<>(); // Order is important on server side, so we use a LinkedHashMap
    private final Server _server;
    private final Executor _executor;
    private final Scheduler _scheduler;
    private final ByteBufferPool _byteBufferPool;
    private final Thread[] _acceptors;
    private final Set<EndPoint> _endpoints = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<EndPoint> _immutableEndPoints = Collections.unmodifiableSet(_endpoints);
    private final Graceful.Shutdown _shutdown = new Graceful.Shutdown();
    private HttpChannel.Listener _httpChannelListeners = HttpChannel.NOOP_LISTENER;
    private CountDownLatch _stopping;
    private long _idleTimeout = 30000;
    private String _defaultProtocol;
    private ConnectionFactory _defaultConnectionFactory;
    private String _name;
    private int _acceptorPriorityDelta = -2;
    private boolean _accepting = true;
    private ThreadPoolBudget.Lease _lease;

    /**
     * @param server The server this connector will be added to. Must not be null.
     * @param executor An executor for this connector or null to use the servers executor
     * @param scheduler A scheduler for this connector or null to either a {@link Scheduler} set as a server bean or if none set, then a new {@link ScheduledExecutorScheduler} instance.
     * @param pool A buffer pool for this connector or null to either a {@link ByteBufferPool} set as a server bean or none set, the new  {@link ArrayByteBufferPool} instance.
     * @param acceptors the number of acceptor threads to use, or -1 for a default value. If 0, then no acceptor threads will be launched and some other mechanism will need to be used to accept new connections.
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
        _server = server;
        _executor = executor != null ? executor : _server.getThreadPool();
        if (scheduler == null)
            scheduler = _server.getBean(Scheduler.class);
        _scheduler = scheduler != null ? scheduler : new ScheduledExecutorScheduler(String.format("Connector-Scheduler-%x", hashCode()), false);
        if (pool == null)
            pool = _server.getBean(ByteBufferPool.class);
        _byteBufferPool = pool != null ? pool : new ArrayByteBufferPool();

        addEventListener(new Container.Listener()
        {
            @Override
            public void beanAdded(Container parent, Object bean)
            {
                if (bean instanceof HttpChannel.Listener)
                    _httpChannelListeners = new HttpChannelListeners(getBeans(HttpChannel.Listener.class));
            }

            @Override
            public void beanRemoved(Container parent, Object bean)
            {
                if (bean instanceof HttpChannel.Listener)
                    _httpChannelListeners = new HttpChannelListeners(getBeans(HttpChannel.Listener.class));
            }
        });

        addBean(_server, false);
        addBean(_executor);
        if (executor == null)
            unmanage(_executor); // inherited from server
        addBean(_scheduler);
        addBean(_byteBufferPool);

        for (ConnectionFactory factory : factories)
        {
            addConnectionFactory(factory);
        }

        int cores = ProcessorUtils.availableProcessors();
        if (acceptors < 0)
            acceptors = Math.max(1, Math.min(4, cores / 8));
        if (acceptors > cores)
            LOG.warn("Acceptors should be <= availableProcessors: " + this);
        _acceptors = new Thread[acceptors];
    }

    /**
     * Get the {@link HttpChannel.Listener}s added to the connector
     * as a single combined Listener.
     * This is equivalent to a listener that iterates over the individual
     * listeners returned from <code>getBeans(HttpChannel.Listener.class);</code>,
     * except that: <ul>
     *     <li>The result is precomputed, so it is more efficient</li>
     *     <li>The result is ordered by the order added.</li>
     *     <li>The result is immutable.</li>
     * </ul>
     * @see #getBeans(Class)
     * @return An unmodifiable list of EventListener beans
     */
    public HttpChannel.Listener getHttpChannelListeners()
    {
        return _httpChannelListeners;
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
    @ManagedAttribute("The connection idle timeout in milliseconds")
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
        _shutdown.cancel();

        if (_defaultProtocol == null)
            throw new IllegalStateException("No default protocol for " + this);
        _defaultConnectionFactory = getConnectionFactory(_defaultProtocol);
        if (_defaultConnectionFactory == null)
            throw new IllegalStateException("No protocol factory for default protocol '" + _defaultProtocol + "' in " + this);
        SslConnectionFactory ssl = getConnectionFactory(SslConnectionFactory.class);
        if (ssl != null)
        {
            String next = ssl.getNextProtocol();
            ConnectionFactory cf = getConnectionFactory(next);
            if (cf == null)
                throw new IllegalStateException("No protocol factory for SSL next protocol: '" + next + "' in " + this);
        }

        _lease = ThreadPoolBudget.leaseFrom(getExecutor(), this, _acceptors.length);
        super.doStart();

        _stopping = new CountDownLatch(_acceptors.length);
        for (int i = 0; i < _acceptors.length; i++)
        {
            Acceptor a = new Acceptor(i);
            addBean(a);
            getExecutor().execute(a);
        }

        LOG.info("Started {}", this);
    }

    protected void interruptAcceptors()
    {
        try (Locker.Lock lock = _locker.lock())
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
        return _shutdown.shutdown();
    }

    @Override
    public boolean isShutdown()
    {
        return _shutdown.isShutdown();
    }

    @Override
    protected void doStop() throws Exception
    {
        if (_lease != null)
            _lease.close();

        // Tell the acceptors we are stopping
        interruptAcceptors();

        // If we have a stop timeout
        long stopTimeout = getStopTimeout();
        CountDownLatch stopping = _stopping;
        if (stopTimeout > 0 && stopping != null && getAcceptors() > 0)
            stopping.await(stopTimeout, TimeUnit.MILLISECONDS);
        _stopping = null;

        super.doStop();

        for (Acceptor a : getBeans(Acceptor.class))
        {
            removeBean(a);
        }

        LOG.info("Stopped {}", this);
    }

    public void join() throws InterruptedException
    {
        join(0);
    }

    public void join(long timeout) throws InterruptedException
    {
        try (Locker.Lock lock = _locker.lock())
        {
            for (Thread thread : _acceptors)
            {
                if (thread != null)
                    thread.join(timeout);
            }
        }
    }

    protected abstract void accept(int acceptorID) throws IOException, InterruptedException;

    /**
     * @return Is the connector accepting new connections
     */
    public boolean isAccepting()
    {
        try (Locker.Lock lock = _locker.lock())
        {
            return _accepting;
        }
    }

    public void setAccepting(boolean accepting)
    {
        try (Locker.Lock lock = _locker.lock())
        {
            _accepting = accepting;
            _setAccepting.signalAll();
        }
    }

    @Override
    public ConnectionFactory getConnectionFactory(String protocol)
    {
        try (Locker.Lock lock = _locker.lock())
        {
            return _factories.get(StringUtil.asciiToLowerCase(protocol));
        }
    }

    @Override
    public <T> T getConnectionFactory(Class<T> factoryType)
    {
        try (Locker.Lock lock = _locker.lock())
        {
            for (ConnectionFactory f : _factories.values())
            {
                if (factoryType.isAssignableFrom(f.getClass()))
                    return (T)f;
            }
            return null;
        }
    }

    public void addConnectionFactory(ConnectionFactory factory)
    {
        if (isRunning())
            throw new IllegalStateException(getState());

        Set<ConnectionFactory> toRemove = new HashSet<>();
        for (String key : factory.getProtocols())
        {
            key = StringUtil.asciiToLowerCase(key);
            ConnectionFactory old = _factories.remove(key);
            if (old != null)
            {
                if (old.getProtocol().equals(_defaultProtocol))
                    _defaultProtocol = null;
                toRemove.add(old);
            }
            _factories.put(key, factory);
        }

        // keep factories still referenced
        for (ConnectionFactory f : _factories.values())
        {
            toRemove.remove(f);
        }

        // remove old factories
        for (ConnectionFactory old : toRemove)
        {
            removeBean(old);
            if (LOG.isDebugEnabled())
                LOG.debug("{} removed {}", this, old);
        }

        // add new Bean
        addBean(factory);
        if (_defaultProtocol == null)
            _defaultProtocol = factory.getProtocol();
        if (LOG.isDebugEnabled())
            LOG.debug("{} added {}", this, factory);
    }

    public void addFirstConnectionFactory(ConnectionFactory factory)
    {
        if (isRunning())
            throw new IllegalStateException(getState());

        List<ConnectionFactory> existings = new ArrayList<>(_factories.values());
        _factories.clear();
        addConnectionFactory(factory);
        for (ConnectionFactory existing : existings)
        {
            addConnectionFactory(existing);
        }
        _defaultProtocol = factory.getProtocol();
    }

    public void addIfAbsentConnectionFactory(ConnectionFactory factory)
    {
        if (isRunning())
            throw new IllegalStateException(getState());

        String key = StringUtil.asciiToLowerCase(factory.getProtocol());
        if (_factories.containsKey(key))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} addIfAbsent ignored {}", this, factory);
        }
        else
        {
            _factories.put(key, factory);
            addBean(factory);
            if (_defaultProtocol == null)
                _defaultProtocol = factory.getProtocol();
            if (LOG.isDebugEnabled())
                LOG.debug("{} addIfAbsent added {}", this, factory);
        }
    }

    public ConnectionFactory removeConnectionFactory(String protocol)
    {
        if (isRunning())
            throw new IllegalStateException(getState());

        ConnectionFactory factory = _factories.remove(StringUtil.asciiToLowerCase(protocol));
        removeBean(factory);
        return factory;
    }

    @Override
    public Collection<ConnectionFactory> getConnectionFactories()
    {
        return _factories.values();
    }

    public void setConnectionFactories(Collection<ConnectionFactory> factories)
    {
        if (isRunning())
            throw new IllegalStateException(getState());

        List<ConnectionFactory> existing = new ArrayList<>(_factories.values());
        for (ConnectionFactory factory : existing)
        {
            removeConnectionFactory(factory.getProtocol());
        }
        for (ConnectionFactory factory : factories)
        {
            if (factory != null)
                addConnectionFactory(factory);
        }
    }

    @ManagedAttribute("The priority delta to apply to acceptor threads")
    public int getAcceptorPriorityDelta()
    {
        return _acceptorPriorityDelta;
    }

    /**
     * Set the acceptor thread priority delta.
     * <p>This allows the acceptor thread to run at a different priority.
     * Typically this would be used to lower the priority to give preference
     * to handling previously accepted connections rather than accepting
     * new connections</p>
     *
     * @param acceptorPriorityDelta the acceptor priority delta
     */
    public void setAcceptorPriorityDelta(int acceptorPriorityDelta)
    {
        int old = _acceptorPriorityDelta;
        _acceptorPriorityDelta = acceptorPriorityDelta;
        if (old != acceptorPriorityDelta && isStarted())
        {
            for (Thread thread : _acceptors)
            {
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Math.min(Thread.MAX_PRIORITY, thread.getPriority() - old + acceptorPriorityDelta)));
            }
        }
    }

    @Override
    @ManagedAttribute("Protocols supported by this connector")
    public List<String> getProtocols()
    {
        return new ArrayList<>(_factories.keySet());
    }

    public void clearConnectionFactories()
    {
        if (isRunning())
            throw new IllegalStateException(getState());

        _factories.clear();
    }

    @ManagedAttribute("This connector's default protocol")
    public String getDefaultProtocol()
    {
        return _defaultProtocol;
    }

    public void setDefaultProtocol(String defaultProtocol)
    {
        _defaultProtocol = StringUtil.asciiToLowerCase(defaultProtocol);
        if (isRunning())
            _defaultConnectionFactory = getConnectionFactory(_defaultProtocol);
    }

    @Override
    public ConnectionFactory getDefaultConnectionFactory()
    {
        if (isStarted())
            return _defaultConnectionFactory;
        return getConnectionFactory(_defaultProtocol);
    }

    protected boolean handleAcceptFailure(Throwable ex)
    {
        if (isRunning())
        {
            if (ex instanceof InterruptedException)
            {
                LOG.debug(ex);
                return true;
            }

            if (ex instanceof ClosedByInterruptException)
            {
                LOG.debug(ex);
                return false;
            }

            LOG.warn(ex);
            try
            {
                // Arbitrary sleep to avoid spin looping.
                // Subclasses may decide for a different
                // sleep policy or closing the connector.
                Thread.sleep(1000);
                return true;
            }
            catch (Throwable x)
            {
                LOG.ignore(x);
            }
            return false;
        }
        else
        {
            LOG.ignore(ex);
            return false;
        }
    }

    private class Acceptor implements Runnable
    {
        private final int _id;
        private String _name;

        private Acceptor(int id)
        {
            _id = id;
        }

        @Override
        public void run()
        {
            final Thread thread = Thread.currentThread();
            String name = thread.getName();
            _name = String.format("%s-acceptor-%d@%x-%s", name, _id, hashCode(), AbstractConnector.this.toString());
            thread.setName(_name);

            int priority = thread.getPriority();
            if (_acceptorPriorityDelta != 0)
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Math.min(Thread.MAX_PRIORITY, priority + _acceptorPriorityDelta)));

            _acceptors[_id] = thread;

            try
            {
                while (isRunning())
                {
                    try (Locker.Lock lock = _locker.lock())
                    {
                        if (!_accepting && isRunning())
                        {
                            _setAccepting.await();
                            continue;
                        }
                    }
                    catch (InterruptedException e)
                    {
                        continue;
                    }

                    try
                    {
                        accept(_id);
                    }
                    catch (Throwable x)
                    {
                        if (!handleAcceptFailure(x))
                            break;
                    }
                }
            }
            finally
            {
                thread.setName(name);
                if (_acceptorPriorityDelta != 0)
                    thread.setPriority(priority);

                synchronized (AbstractConnector.this)
                {
                    _acceptors[_id] = null;
                }
                CountDownLatch stopping = _stopping;
                if (stopping != null)
                    stopping.countDown();
            }
        }

        @Override
        public String toString()
        {
            String name = _name;
            if (name == null)
                return String.format("acceptor-%d@%x", _id, hashCode());
            return name;
        }
    }

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

    /**
     * Set a connector name.   A context may be configured with
     * virtual hosts in the form "@contextname" and will only serve
     * requests from the named connector,
     *
     * @param name A connector name.
     */
    public void setName(String name)
    {
        _name = name;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s, %s}",
            _name == null ? getClass().getSimpleName() : _name,
            hashCode(),
            getDefaultProtocol(), getProtocols().stream().collect(Collectors.joining(", ", "(", ")")));
    }
}
