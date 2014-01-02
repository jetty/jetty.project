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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.net.ssl.SSLContext;

import org.eclipse.jetty.client.security.Authentication;
import org.eclipse.jetty.client.security.RealmResolver;
import org.eclipse.jetty.client.security.SecurityListener;
import org.eclipse.jetty.http.HttpBuffers;
import org.eclipse.jetty.http.HttpBuffersImpl;
import org.eclipse.jetty.http.HttpSchemes;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.Buffers.Type;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.eclipse.jetty.util.thread.Timeout;

/**
 * Http Client.
 * <p/>
 * HttpClient is the main active component of the client API implementation.
 * It is the opposite of the Connectors in standard Jetty, in that it listens
 * for responses rather than requests.   Just like the connectors, there is a
 * blocking socket version and a non-blocking NIO version (implemented as nested classes
 * selected by {@link #setConnectorType(int)}).
 * <p/>
 * The an instance of {@link HttpExchange} is passed to the {@link #send(HttpExchange)} method
 * to send a request.  The exchange contains both the headers and content (source) of the request
 * plus the callbacks to handle responses.   A HttpClient can have many exchanges outstanding
 * and they may be queued on the {@link HttpDestination} waiting for a {@link AbstractHttpConnection},
 * queued in the {@link AbstractHttpConnection} waiting to be transmitted or pipelined on the actual
 * TCP/IP connection waiting for a response.
 * <p/>
 * The {@link HttpDestination} class is an aggregation of {@link AbstractHttpConnection}s for the
 * same host, port and protocol.   A destination may limit the number of connections
 * open and they provide a pool of open connections that may be reused.   Connections may also
 * be allocated from a destination, so that multiple request sources are not multiplexed
 * over the same connection.
 *
 * @see HttpExchange
 * @see HttpDestination
 */
public class HttpClient extends AggregateLifeCycle implements HttpBuffers, Attributes, Dumpable
{
    public static final int CONNECTOR_SOCKET = 0;
    public static final int CONNECTOR_SELECT_CHANNEL = 2;

    private int _connectorType = CONNECTOR_SELECT_CHANNEL;
    private boolean _useDirectBuffers = true;
    private boolean _connectBlocking = true;
    private int _maxConnectionsPerAddress = Integer.MAX_VALUE;
    private int _maxQueueSizePerAddress = Integer.MAX_VALUE;
    private ConcurrentMap<Address, HttpDestination> _destinations = new ConcurrentHashMap<Address, HttpDestination>();
    ThreadPool _threadPool;
    Connector _connector;
    private long _idleTimeout = 20000;
    private long _timeout = 320000;
    private int _connectTimeout = 75000;
    private Timeout _timeoutQ = new Timeout();
    private Timeout _idleTimeoutQ = new Timeout();
    private Address _proxy;
    private Authentication _proxyAuthentication;
    private Set<String> _noProxy;
    private int _maxRetries = 3;
    private int _maxRedirects = 20;
    private LinkedList<String> _registeredListeners;

    private final SslContextFactory _sslContextFactory;

    private RealmResolver _realmResolver;

    private AttributesMap _attributes=new AttributesMap();

    private final HttpBuffersImpl _buffers= new HttpBuffersImpl();

    /* ------------------------------------------------------------------------------- */
    private void setBufferTypes()
    {
        if (_connectorType==CONNECTOR_SOCKET)
        {
            _buffers.setRequestBufferType(Type.BYTE_ARRAY);
            _buffers.setRequestHeaderType(Type.BYTE_ARRAY);
            _buffers.setResponseBufferType(Type.BYTE_ARRAY);
            _buffers.setResponseHeaderType(Type.BYTE_ARRAY);
        }
        else
        {
            _buffers.setRequestBufferType(Type.DIRECT);
            _buffers.setRequestHeaderType(_useDirectBuffers?Type.DIRECT:Type.INDIRECT);
            _buffers.setResponseBufferType(Type.DIRECT);
            _buffers.setResponseHeaderType(_useDirectBuffers?Type.DIRECT:Type.INDIRECT);
        }

    }

    /* ------------------------------------------------------------------------------- */
    public HttpClient()
    {
        this(new SslContextFactory());
    }

    /* ------------------------------------------------------------------------------- */
    public HttpClient(SslContextFactory sslContextFactory)
    {
        _sslContextFactory = sslContextFactory;
        addBean(_sslContextFactory);
        addBean(_buffers);
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * @return True if connects will be in blocking mode.
     */
    public boolean isConnectBlocking()
    {
        return _connectBlocking;
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * @param connectBlocking True if connects will be in blocking mode.
     */
    public void setConnectBlocking(boolean connectBlocking)
    {
        _connectBlocking = connectBlocking;
    }

    /* ------------------------------------------------------------------------------- */
    public void send(HttpExchange exchange) throws IOException
    {
        boolean ssl = HttpSchemes.HTTPS_BUFFER.equalsIgnoreCase(exchange.getScheme());
        HttpDestination destination = getDestination(exchange.getAddress(), ssl);
        destination.send(exchange);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the threadpool
     */
    public ThreadPool getThreadPool()
    {
        return _threadPool;
    }

    /* ------------------------------------------------------------ */
    /** Set the ThreadPool.
     * The threadpool passed is added via {@link #addBean(Object)} so that
     * it's lifecycle may be managed as a {@link AggregateLifeCycle}.
     * @param threadPool the threadPool to set
     */
    public void setThreadPool(ThreadPool threadPool)
    {
        removeBean(_threadPool);
        _threadPool = threadPool;
        addBean(_threadPool);
    }


    /* ------------------------------------------------------------ */
    /**
     * @param name
     * @return Attribute associated with client
     */
    public Object getAttribute(String name)
    {
        return _attributes.getAttribute(name);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return names of attributes associated with client
     */
    public Enumeration getAttributeNames()
    {
        return _attributes.getAttributeNames();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param name
     */
    public void removeAttribute(String name)
    {
        _attributes.removeAttribute(name);
    }

    /* ------------------------------------------------------------ */
    /**
     * Set an attribute on the HttpClient.
     * Attributes are not used by the client, but are provided for
     * so that users of a shared HttpClient may share other structures.
     * @param name
     * @param attribute
     */
    public void setAttribute(String name, Object attribute)
    {
        _attributes.setAttribute(name,attribute);
    }

    /* ------------------------------------------------------------ */
    public void clearAttributes()
    {
        _attributes.clearAttributes();
    }

    /* ------------------------------------------------------------------------------- */
    public HttpDestination getDestination(Address remote, boolean ssl) throws IOException
    {
        return getDestination(remote, ssl, getSslContextFactory());
    }

    /* ------------------------------------------------------------------------------- */
    public HttpDestination getDestination(Address remote, boolean ssl, SslContextFactory sslContextFactory) throws IOException
    {
        if (remote == null)
            throw new UnknownHostException("Remote socket address cannot be null.");

        HttpDestination destination = _destinations.get(remote);
        if (destination == null)
        {
            destination = new HttpDestination(this, remote, ssl, sslContextFactory);
            if (_proxy != null && (_noProxy == null || !_noProxy.contains(remote.getHost())))
            {
                destination.setProxy(_proxy);
                if (_proxyAuthentication != null)
                    destination.setProxyAuthentication(_proxyAuthentication);
            }
            HttpDestination other =_destinations.putIfAbsent(remote, destination);
            if (other!=null)
                destination=other;
        }
        return destination;
    }

    /* ------------------------------------------------------------ */
    public void schedule(Timeout.Task task)
    {
        _timeoutQ.schedule(task);
    }

    /* ------------------------------------------------------------ */
    public void schedule(Timeout.Task task, long timeout)
    {
        _timeoutQ.schedule(task, timeout - _timeoutQ.getDuration());
    }

    /* ------------------------------------------------------------ */
    public void scheduleIdle(Timeout.Task task)
    {
        _idleTimeoutQ.schedule(task);
    }

    /* ------------------------------------------------------------ */
    public void cancel(Timeout.Task task)
    {
        task.cancel();
    }

    /* ------------------------------------------------------------ */
    /**
     * Get whether the connector can use direct NIO buffers.
     */
    public boolean getUseDirectBuffers()
    {
        return _useDirectBuffers;
    }

    /* ------------------------------------------------------------ */
    /** Set a RealmResolver for client Authentication.
     * If a realmResolver is set, then the HttpDestinations created by
     * this client will instantiate a {@link SecurityListener} so that
     * BASIC and DIGEST authentication can be performed.
     * @param resolver
     */
    public void setRealmResolver(RealmResolver resolver)
    {
        _realmResolver = resolver;
    }

    /* ------------------------------------------------------------ */
    /**
     * returns the SecurityRealmResolver reg_realmResolveristered with the HttpClient or null
     *
     * @return the SecurityRealmResolver reg_realmResolveristered with the HttpClient or null
     */
    public RealmResolver getRealmResolver()
    {
        return _realmResolver;
    }

    /* ------------------------------------------------------------ */
    public boolean hasRealms()
    {
        return _realmResolver == null ? false : true;
    }


    /* ------------------------------------------------------------ */
    /**
     * Registers a listener that can listen to the stream of execution between the client and the
     * server and influence events.  Sequential calls to the method wrapper sequentially wrap the preceding
     * listener in a delegation model.
     * <p/>
     * NOTE: the SecurityListener is a special listener which doesn't need to be added via this
     * mechanic, if you register security realms then it will automatically be added as the top listener of the
     * delegation stack.
     *
     * @param listenerClass
     */
    public void registerListener(String listenerClass)
    {
        if (_registeredListeners == null)
        {
            _registeredListeners = new LinkedList<String>();
        }
        _registeredListeners.add(listenerClass);
    }

    /* ------------------------------------------------------------ */
    public LinkedList<String> getRegisteredListeners()
    {
        return _registeredListeners;
    }


    /* ------------------------------------------------------------ */
    /**
     * Set to use NIO direct buffers.
     *
     * @param direct If True (the default), the connector can use NIO direct
     *               buffers. Some JVMs have memory management issues (bugs) with
     *               direct buffers.
     */
    public void setUseDirectBuffers(boolean direct)
    {
        _useDirectBuffers = direct;
        setBufferTypes();
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the type of connector (socket, blocking or select) in use.
     */
    public int getConnectorType()
    {
        return _connectorType;
    }

    /* ------------------------------------------------------------ */
    public void setConnectorType(int connectorType)
    {
        this._connectorType = connectorType;
        setBufferTypes();
    }

    /* ------------------------------------------------------------ */
    public int getMaxConnectionsPerAddress()
    {
        return _maxConnectionsPerAddress;
    }

    /* ------------------------------------------------------------ */
    public void setMaxConnectionsPerAddress(int maxConnectionsPerAddress)
    {
        _maxConnectionsPerAddress = maxConnectionsPerAddress;
    }

    public int getMaxQueueSizePerAddress()
    {
        return _maxQueueSizePerAddress;
    }

    public void setMaxQueueSizePerAddress(int maxQueueSizePerAddress)
    {
        this._maxQueueSizePerAddress = maxQueueSizePerAddress;
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStart() throws Exception
    {
        setBufferTypes();

        _timeoutQ.setDuration(_timeout);
        _timeoutQ.setNow();
        _idleTimeoutQ.setDuration(_idleTimeout);
        _idleTimeoutQ.setNow();

        if (_threadPool==null)
        {
            QueuedThreadPool pool = new LocalQueuedThreadPool();
            pool.setMaxThreads(16);
            pool.setDaemon(true);
            pool.setName("HttpClient");
            _threadPool = pool;
            addBean(_threadPool,true);
        }

        _connector=(_connectorType == CONNECTOR_SELECT_CHANNEL)?new SelectConnector(this):new SocketConnector(this);
        addBean(_connector,true);

        super.doStart();

        _threadPool.dispatch(new Runnable()
        {
            public void run()
            {
                while (isRunning())
                {
                    _timeoutQ.tick(System.currentTimeMillis());
                    _idleTimeoutQ.tick(_timeoutQ.getNow());
                    try
                    {
                        Thread.sleep(200);
                    }
                    catch (InterruptedException ignored)
                    {
                    }
                }
            }
        });
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStop() throws Exception
    {
        for (HttpDestination destination : _destinations.values())
            destination.close();

        _timeoutQ.cancelAll();
        _idleTimeoutQ.cancelAll();

        super.doStop();

        if (_threadPool instanceof LocalQueuedThreadPool)
        {
            removeBean(_threadPool);
            _threadPool = null;
        }

        removeBean(_connector);
    }

    /* ------------------------------------------------------------ */
    interface Connector extends LifeCycle
    {
        public void startConnection(HttpDestination destination) throws IOException;
    }

    /* ------------------------------------------------------------ */
    /**
     * if a keystore location has been provided then client will attempt to use it as the keystore,
     * otherwise we simply ignore certificates and run with a loose ssl context.
     *
     * @return the SSL context
     */
    protected SSLContext getSSLContext()
    {
        return _sslContextFactory.getSslContext();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the instance of SslContextFactory associated with the client
     */
    public SslContextFactory getSslContextFactory()
    {
        return _sslContextFactory;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the period in milliseconds a {@link AbstractHttpConnection} can be idle for before it is closed.
     */
    public long getIdleTimeout()
    {
        return _idleTimeout;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param ms the period in milliseconds a {@link AbstractHttpConnection} can be idle for before it is closed.
     */
    public void setIdleTimeout(long ms)
    {
        _idleTimeout = ms;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the period in ms that an exchange will wait for a response from the server.
     * @deprecated use {@link #getTimeout()} instead.
     */
    @Deprecated
    public int getSoTimeout()
    {
        return Long.valueOf(getTimeout()).intValue();
    }

    /* ------------------------------------------------------------ */
    /**
     * @deprecated use {@link #setTimeout(long)} instead.
     * @param timeout the period in ms that an exchange will wait for a response from the server.
     */
    @Deprecated
    public void setSoTimeout(int timeout)
    {
        setTimeout(timeout);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the period in ms that an exchange will wait for a response from the server.
     */
    public long getTimeout()
    {
        return _timeout;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param timeout the period in ms that an exchange will wait for a response from the server.
     */
    public void setTimeout(long timeout)
    {
        _timeout = timeout;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the period in ms before timing out an attempt to connect
     */
    public int getConnectTimeout()
    {
        return _connectTimeout;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param connectTimeout the period in ms before timing out an attempt to connect
     */
    public void setConnectTimeout(int connectTimeout)
    {
        this._connectTimeout = connectTimeout;
    }

    /* ------------------------------------------------------------ */
    public Address getProxy()
    {
        return _proxy;
    }

    /* ------------------------------------------------------------ */
    public void setProxy(Address proxy)
    {
        this._proxy = proxy;
    }

    /* ------------------------------------------------------------ */
    public Authentication getProxyAuthentication()
    {
        return _proxyAuthentication;
    }

    /* ------------------------------------------------------------ */
    public void setProxyAuthentication(Authentication authentication)
    {
        _proxyAuthentication = authentication;
    }

    /* ------------------------------------------------------------ */
    public boolean isProxied()
    {
        return this._proxy != null;
    }

    /* ------------------------------------------------------------ */
    public Set<String> getNoProxy()
    {
        return _noProxy;
    }

    /* ------------------------------------------------------------ */
    public void setNoProxy(Set<String> noProxyAddresses)
    {
        _noProxy = noProxyAddresses;
    }

    /* ------------------------------------------------------------ */
    public int maxRetries()
    {
        return _maxRetries;
    }

    /* ------------------------------------------------------------ */
    public void setMaxRetries(int retries)
    {
        _maxRetries = retries;
    }

    /* ------------------------------------------------------------ */
    public int maxRedirects()
    {
        return _maxRedirects;
    }

    /* ------------------------------------------------------------ */
    public void setMaxRedirects(int redirects)
    {
        _maxRedirects = redirects;
    }

    public int getRequestBufferSize()
    {
        return _buffers.getRequestBufferSize();
    }

    public void setRequestBufferSize(int requestBufferSize)
    {
        _buffers.setRequestBufferSize(requestBufferSize);
    }

    public int getRequestHeaderSize()
    {
        return _buffers.getRequestHeaderSize();
    }

    public void setRequestHeaderSize(int requestHeaderSize)
    {
        _buffers.setRequestHeaderSize(requestHeaderSize);
    }

    public int getResponseBufferSize()
    {
        return _buffers.getResponseBufferSize();
    }

    public void setResponseBufferSize(int responseBufferSize)
    {
        _buffers.setResponseBufferSize(responseBufferSize);
    }

    public int getResponseHeaderSize()
    {
        return _buffers.getResponseHeaderSize();
    }

    public void setResponseHeaderSize(int responseHeaderSize)
    {
        _buffers.setResponseHeaderSize(responseHeaderSize);
    }

    public Type getRequestBufferType()
    {
        return _buffers.getRequestBufferType();
    }

    public Type getRequestHeaderType()
    {
        return _buffers.getRequestHeaderType();
    }

    public Type getResponseBufferType()
    {
        return _buffers.getResponseBufferType();
    }

    public Type getResponseHeaderType()
    {
        return _buffers.getResponseHeaderType();
    }

    public void setRequestBuffers(Buffers requestBuffers)
    {
        _buffers.setRequestBuffers(requestBuffers);
    }

    public void setResponseBuffers(Buffers responseBuffers)
    {
        _buffers.setResponseBuffers(responseBuffers);
    }

    public Buffers getRequestBuffers()
    {
        return _buffers.getRequestBuffers();
    }

    public Buffers getResponseBuffers()
    {
        return _buffers.getResponseBuffers();
    }

    public void setMaxBuffers(int maxBuffers)
    {
        _buffers.setMaxBuffers(maxBuffers);
    }

    public int getMaxBuffers()
    {
        return _buffers.getMaxBuffers();
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public String getTrustStoreLocation()
    {
        return _sslContextFactory.getTrustStore();
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void setTrustStoreLocation(String trustStoreLocation)
    {
        _sslContextFactory.setTrustStore(trustStoreLocation);
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public InputStream getTrustStoreInputStream()
    {
        return _sslContextFactory.getTrustStoreInputStream();
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void setTrustStoreInputStream(InputStream trustStoreInputStream)
    {
        _sslContextFactory.setTrustStoreInputStream(trustStoreInputStream);
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public String getKeyStoreLocation()
    {
        return _sslContextFactory.getKeyStorePath();
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void setKeyStoreLocation(String keyStoreLocation)
    {
        _sslContextFactory.setKeyStorePath(keyStoreLocation);
    }

    @Deprecated
    public InputStream getKeyStoreInputStream()
    {
        return _sslContextFactory.getKeyStoreInputStream();
    }

    @Deprecated
    public void setKeyStoreInputStream(InputStream keyStoreInputStream)
    {
        _sslContextFactory.setKeyStoreInputStream(keyStoreInputStream);
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void setKeyStorePassword(String keyStorePassword)
    {
        _sslContextFactory.setKeyStorePassword(keyStorePassword);
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void setKeyManagerPassword(String keyManagerPassword)
    {
        _sslContextFactory.setKeyManagerPassword(keyManagerPassword);
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void setTrustStorePassword(String trustStorePassword)
    {
        _sslContextFactory.setTrustStorePassword(trustStorePassword);
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public String getKeyStoreType()
    {
        return _sslContextFactory.getKeyStoreType();
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void setKeyStoreType(String keyStoreType)
    {
        _sslContextFactory.setKeyStoreType(keyStoreType);
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public String getTrustStoreType()
    {
        return _sslContextFactory.getTrustStoreType();
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void setTrustStoreType(String trustStoreType)
    {
        _sslContextFactory.setTrustStoreType(trustStoreType);
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public String getKeyManagerAlgorithm()
    {
        return _sslContextFactory.getSslKeyManagerFactoryAlgorithm();
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void setKeyManagerAlgorithm(String keyManagerAlgorithm)
    {
        _sslContextFactory.setSslKeyManagerFactoryAlgorithm(keyManagerAlgorithm);
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public String getTrustManagerAlgorithm()
    {
        return _sslContextFactory.getTrustManagerFactoryAlgorithm();
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void setTrustManagerAlgorithm(String trustManagerAlgorithm)
    {
        _sslContextFactory.setTrustManagerFactoryAlgorithm(trustManagerAlgorithm);
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public String getProtocol()
    {
        return _sslContextFactory.getProtocol();
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void setProtocol(String protocol)
    {
        _sslContextFactory.setProtocol(protocol);
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public String getProvider()
    {
        return _sslContextFactory.getProvider();
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void setProvider(String provider)
    {
        _sslContextFactory.setProvider(provider);
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public String getSecureRandomAlgorithm()
    {
        return _sslContextFactory.getSecureRandomAlgorithm();
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void setSecureRandomAlgorithm(String secureRandomAlgorithm)
    {
        _sslContextFactory.setSecureRandomAlgorithm(secureRandomAlgorithm);
    }

    private static class LocalQueuedThreadPool extends QueuedThreadPool
    {
    }
}
