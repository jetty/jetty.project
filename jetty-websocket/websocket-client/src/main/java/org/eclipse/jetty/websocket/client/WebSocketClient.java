//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client;

import java.io.IOException;
import java.net.CookieStore;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.ShutdownThread;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.client.io.ConnectionManager;
import org.eclipse.jetty.websocket.client.io.UpgradeListener;
import org.eclipse.jetty.websocket.client.masks.Masker;
import org.eclipse.jetty.websocket.client.masks.RandomMasker;
import org.eclipse.jetty.websocket.common.SessionFactory;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.WebSocketSessionFactory;
import org.eclipse.jetty.websocket.common.events.EventDriverFactory;
import org.eclipse.jetty.websocket.common.extensions.WebSocketExtensionFactory;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;

/**
 * WebSocketClient provides a means of establishing connections to remote websocket endpoints.
 */
public class WebSocketClient extends ContainerLifeCycle implements WebSocketContainerScope
{
    private static final Logger LOG = Log.getLogger(WebSocketClient.class);

    // From HttpClient
    private final HttpClient httpClient;

    // Other
    private final WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
    private final WebSocketExtensionFactory extensionRegistry;
    private final EventDriverFactory eventDriverFactory;
    private final SessionFactory sessionFactory;
    private final DecoratedObjectFactory objectFactory;
    private Masker masker;

    private final int id = ThreadLocalRandom.current().nextInt();

    /**
     * Instantiate a WebSocketClient with defaults
     */
    public WebSocketClient()
    {
        // Create synthetic HttpClient
        this(new HttpClient());
        addBean(this.httpClient);
    }

    /**
     * Instantiate a WebSocketClient using HttpClient for defaults
     *
     * @param httpClient
     *            the HttpClient to base internal defaults off of
     */
    public WebSocketClient(HttpClient httpClient)
    {
        this(httpClient,new DecoratedObjectFactory());
    }

    /**
     * Instantiate a WebSocketClient using HttpClient for defaults
     *
     * @param httpClient
     *            the HttpClient to base internal defaults off of
     * @param objectFactory
     *            the DecoratedObjectFactory for all client instantiated classes
     */
    public WebSocketClient(HttpClient httpClient, DecoratedObjectFactory objectFactory)
    {
        this.httpClient = httpClient;
        this.objectFactory = objectFactory;
        this.extensionRegistry = new WebSocketExtensionFactory(this);
        this.masker = new RandomMasker();
        this.eventDriverFactory = new EventDriverFactory(policy);
        this.sessionFactory = new WebSocketSessionFactory(this);
    }

    /**
     * Create a new WebSocketClient
     *
     * @param executor
     *            the executor to use
     * @deprecated use {@link #WebSocketClient(HttpClient)} instead
     */
    @Deprecated
    public WebSocketClient(Executor executor)
    {
        this(null,executor);
    }

    /**
     * Create a new WebSocketClient
     *
     * @param bufferPool
     *            byte buffer pool to use
     * @deprecated use {@link #WebSocketClient(HttpClient)} instead
     */
    @Deprecated
    public WebSocketClient(ByteBufferPool bufferPool)
    {
        this(null,null,bufferPool);
    }

    /**
     * Create a new WebSocketClient
     *
     * @param sslContextFactory
     *            ssl context factory to use
     */
    public WebSocketClient(SslContextFactory sslContextFactory)
    {
        this(sslContextFactory,null);
    }

    /**
     * Create a new WebSocketClient
     *
     * @param sslContextFactory
     *            ssl context factory to use
     * @param executor
     *            the executor to use
     * @deprecated use {@link #WebSocketClient(HttpClient)} instead
     */
    @Deprecated
    public WebSocketClient(SslContextFactory sslContextFactory, Executor executor)
    {
        this(sslContextFactory,executor,new MappedByteBufferPool());
    }

    /**
     * Create WebSocketClient other Container Scope, to allow sharing of
     * internal features like Executor, ByteBufferPool, SSLContextFactory, etc.
     *
     * @param scope
     *            the Container Scope
     */
    public WebSocketClient(WebSocketContainerScope scope)
    {
        this(scope.getSslContextFactory(),scope.getExecutor(),scope.getBufferPool(),scope.getObjectFactory());
    }

    /**
     * Create WebSocketClient other Container Scope, to allow sharing of
     * internal features like Executor, ByteBufferPool, SSLContextFactory, etc.
     *
     * @param scope
     *            the Container Scope
     * @param sslContextFactory
     *            SSL ContextFactory to use in preference to one from
     *            {@link WebSocketContainerScope#getSslContextFactory()}
     */
    public WebSocketClient(WebSocketContainerScope scope, SslContextFactory sslContextFactory)
    {
        this(sslContextFactory,scope.getExecutor(),scope.getBufferPool(),scope.getObjectFactory());
    }

    /**
     * Create WebSocketClient using sharing instances of SSLContextFactory
     * Executor, and ByteBufferPool
     *
     * @param sslContextFactory
     *            shared SSL ContextFactory
     * @param executor
     *            shared Executor
     * @param bufferPool
     *            shared ByteBufferPool
     */
    public WebSocketClient(SslContextFactory sslContextFactory, Executor executor, ByteBufferPool bufferPool)
    {
        this(sslContextFactory,executor,bufferPool,new DecoratedObjectFactory());
    }

    /**
     * Create WebSocketClient using sharing instances of SSLContextFactory
     * Executor, and ByteBufferPool
     *
     * @param sslContextFactory
     *            shared SSL ContextFactory
     * @param executor
     *            shared Executor
     * @param bufferPool
     *            shared ByteBufferPool
     * @param objectFactory
     *            shared DecoratedObjectFactory
     */
    private WebSocketClient(SslContextFactory sslContextFactory, Executor executor, ByteBufferPool bufferPool, DecoratedObjectFactory objectFactory)
    {
        this.httpClient = new HttpClient(sslContextFactory);
        this.httpClient.setExecutor(executor);
        this.httpClient.setByteBufferPool(bufferPool);
        addBean(this.httpClient);

        if (objectFactory == null)
            this.objectFactory = new DecoratedObjectFactory();
        else
            this.objectFactory = objectFactory;

        this.extensionRegistry = new WebSocketExtensionFactory(this);

        this.masker = new RandomMasker();
        this.eventDriverFactory = new EventDriverFactory(policy);
        this.sessionFactory = new WebSocketSessionFactory(this);
    }

    /**
     * Create WebSocketClient based on pre-existing Container Scope, to allow sharing of
     * internal features like Executor, ByteBufferPool, SSLContextFactory, etc.
     *
     * @param scope
     *            the Container Scope
     * @param eventDriverFactory
     *            the EventDriver Factory to use
     * @param sessionFactory
     *            the SessionFactory to use
     */
    public WebSocketClient(WebSocketContainerScope scope, EventDriverFactory eventDriverFactory, SessionFactory sessionFactory)
    {
        SslContextFactory sslContextFactory = scope.getSslContextFactory();
        if(sslContextFactory == null)
        {
            sslContextFactory = new SslContextFactory();
        }
        this.httpClient = new HttpClient(sslContextFactory);
        this.httpClient.setExecutor(scope.getExecutor());
        addBean(this.httpClient);

        this.objectFactory = new DecoratedObjectFactory();
        this.extensionRegistry = new WebSocketExtensionFactory(this);

        this.masker = new RandomMasker();
        this.eventDriverFactory = eventDriverFactory;
        this.sessionFactory = sessionFactory;
    }

    public Future<Session> connect(Object websocket, URI toUri) throws IOException
    {
        ClientUpgradeRequest request = new ClientUpgradeRequest(toUri);
        request.setRequestURI(toUri);
        request.setLocalEndpoint(websocket);

        return connect(websocket,toUri,request);
    }

    /**
     * Connect to remote websocket endpoint
     *
     * @param websocket
     *            the websocket object
     * @param toUri
     *            the websocket uri to connect to
     * @param request
     *            the upgrade request information
     * @return the future for the session, available on success of connect
     * @throws IOException
     *             if unable to connect
     */
    public Future<Session> connect(Object websocket, URI toUri, ClientUpgradeRequest request) throws IOException
    {
        return connect(websocket,toUri,request,(UpgradeListener)null);
    }

    /**
     * Connect to remote websocket endpoint
     *
     * @param websocket
     *            the websocket object
     * @param toUri
     *            the websocket uri to connect to
     * @param request
     *            the upgrade request information
     * @param upgradeListener
     *            the upgrade listener
     * @return the future for the session, available on success of connect
     * @throws IOException
     *             if unable to connect
     */
    public Future<Session> connect(Object websocket, URI toUri, ClientUpgradeRequest request, UpgradeListener upgradeListener) throws IOException
    {
        /* Note: UpgradeListener is used by javax.websocket.ClientEndpointConfig.Configurator
         * See: org.eclipse.jetty.websocket.jsr356.JsrUpgradeListener
         */
        if (!isStarted())
        {
            throw new IllegalStateException(WebSocketClient.class.getSimpleName() + "@" + this.hashCode() + " is not started");
        }

        // Validate websocket URI
        if (!toUri.isAbsolute())
        {
            throw new IllegalArgumentException("WebSocket URI must be absolute");
        }

        if (StringUtil.isBlank(toUri.getScheme()))
        {
            throw new IllegalArgumentException("WebSocket URI must include a scheme");
        }

        String scheme = toUri.getScheme().toLowerCase(Locale.ENGLISH);
        if (("ws".equals(scheme) == false) && ("wss".equals(scheme) == false))
        {
            throw new IllegalArgumentException("WebSocket URI scheme only supports [ws] and [wss], not [" + scheme + "]");
        }

        request.setRequestURI(toUri);
        request.setLocalEndpoint(websocket);

        // Validate Requested Extensions
        for (ExtensionConfig reqExt : request.getExtensions())
        {
            if (!extensionRegistry.isAvailable(reqExt.getName()))
            {
                throw new IllegalArgumentException("Requested extension [" + reqExt.getName() + "] is not installed");
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("connect websocket {} to {}",websocket,toUri);

        init();

        WebSocketUpgradeRequest wsReq = new WebSocketUpgradeRequest(this,httpClient,request);

        wsReq.setUpgradeListener(upgradeListener);
        return wsReq.sendAsync();
    }

    @Override
    protected void doStop() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Stopping {}",this);


        if (ShutdownThread.isRegistered(this))
        {
            ShutdownThread.deregister(this);
        }

        super.doStop();

        if (LOG.isDebugEnabled())
            LOG.debug("Stopped {}",this);
    }

    @Deprecated
    public boolean isDispatchIO()
    {
        return httpClient.isDispatchIO();
    }

    /**
     * Return the number of milliseconds for a timeout of an attempted write operation.
     *
     * @return number of milliseconds for timeout of an attempted write operation
     */
    public long getAsyncWriteTimeout()
    {
        return this.policy.getAsyncWriteTimeout();
    }

    public SocketAddress getBindAddress()
    {
        return httpClient.getBindAddress();
    }

    public ByteBufferPool getBufferPool()
    {
        return httpClient.getByteBufferPool();
    }

    @Deprecated
    public ConnectionManager getConnectionManager()
    {
        throw new UnsupportedOperationException("ConnectionManager is no longer supported");
    }

    public long getConnectTimeout()
    {
        return httpClient.getConnectTimeout();
    }

    public CookieStore getCookieStore()
    {
        return httpClient.getCookieStore();
    }

    public EventDriverFactory getEventDriverFactory()
    {
        return eventDriverFactory;
    }

    public Executor getExecutor()
    {
        return httpClient.getExecutor();
    }

    public ExtensionFactory getExtensionFactory()
    {
        return extensionRegistry;
    }

    public Masker getMasker()
    {
        return masker;
    }

    /**
     * Get the maximum size for buffering of a binary message.
     *
     * @return the maximum size of a binary message buffer.
     */
    public int getMaxBinaryMessageBufferSize()
    {
        return this.policy.getMaxBinaryMessageBufferSize();
    }

    /**
     * Get the maximum size for a binary message.
     *
     * @return the maximum size of a binary message.
     */
    public long getMaxBinaryMessageSize()
    {
        return this.policy.getMaxBinaryMessageSize();
    }

    /**
     * Get the max idle timeout for new connections.
     *
     * @return the max idle timeout in milliseconds for new connections.
     */
    public long getMaxIdleTimeout()
    {
        return this.policy.getIdleTimeout();
    }

    /**
     * Get the maximum size for buffering of a text message.
     *
     * @return the maximum size of a text message buffer.
     */
    public int getMaxTextMessageBufferSize()
    {
        return this.policy.getMaxTextMessageBufferSize();
    }

    /**
     * Get the maximum size for a text message.
     *
     * @return the maximum size of a text message.
     */
    public long getMaxTextMessageSize()
    {
        return this.policy.getMaxTextMessageSize();
    }

    @Override
    public DecoratedObjectFactory getObjectFactory()
    {
        return this.objectFactory;
    }

    public Set<WebSocketSession> getOpenSessions()
    {
        return Collections.unmodifiableSet(new HashSet<>(getBeans(WebSocketSession.class)));
    }

    public WebSocketPolicy getPolicy()
    {
        return this.policy;
    }

    public Scheduler getScheduler()
    {
        return httpClient.getScheduler();
    }

    public SessionFactory getSessionFactory()
    {
        return sessionFactory;
    }

    /**
     * @return the {@link SslContextFactory} that manages TLS encryption
     * @see #WebSocketClient(SslContextFactory)
     */
    public SslContextFactory getSslContextFactory()
    {
        return httpClient.getSslContextFactory();
    }

    private synchronized void init() throws IOException
    {
        if (!ShutdownThread.isRegistered(this))
        {
            ShutdownThread.register(this);
        }
    }

    /**
     * Factory method for new ConnectionManager
     *
     * @return the ConnectionManager instance to use
     * @deprecated use HttpClient instead
     */
    @Deprecated
    protected ConnectionManager newConnectionManager()
    {
        throw new UnsupportedOperationException("ConnectionManager is no longer supported");
    }

    @Override
    public void onSessionClosed(WebSocketSession session)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Session Closed: {}",session);
        removeBean(session);
    }

    @Override
    public void onSessionOpened(WebSocketSession session)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Session Opened: {}",session);
        addManaged(session);
        LOG.debug("post-onSessionOpened() - {}", this);
    }

    public void setAsyncWriteTimeout(long ms)
    {
        this.policy.setAsyncWriteTimeout(ms);
    }

    /**
     * @param bindAddress
     *            the address to bind to
     * @deprecated (this is a bad bad bad typo) use {@link #setBindAddress(SocketAddress)} instead
     */
    @Deprecated
    public void setBindAdddress(SocketAddress bindAddress)
    {
        setBindAddress(bindAddress);
    }

    public void setBindAddress(SocketAddress bindAddress)
    {
        this.httpClient.setBindAddress(bindAddress);
    }

    public void setBufferPool(ByteBufferPool bufferPool)
    {
        this.httpClient.setByteBufferPool(bufferPool);
    }

    /**
     * Set the timeout for connecting to the remote server.
     *
     * @param ms
     *            the timeout in milliseconds
     */
    public void setConnectTimeout(long ms)
    {
        this.httpClient.setConnectTimeout(ms);
    }

    public void setCookieStore(CookieStore cookieStore)
    {
        this.httpClient.setCookieStore(cookieStore);
    }

    public void setDaemon(boolean daemon)
    {
        // do nothing
    }

    @Deprecated
    public void setDispatchIO(boolean dispatchIO)
    {
        this.httpClient.setDispatchIO(dispatchIO);
    }

    public void setExecutor(Executor executor)
    {
        this.httpClient.setExecutor(executor);
    }

    public void setMasker(Masker masker)
    {
        this.masker = masker;
    }

    public void setMaxBinaryMessageBufferSize(int max)
    {
        this.policy.setMaxBinaryMessageBufferSize(max);
    }

    /**
     * Set the max idle timeout for new connections.
     * <p>
     * Existing connections will not have their max idle timeout adjusted.
     *
     * @param ms
     *            the timeout in milliseconds
     */
    public void setMaxIdleTimeout(long ms)
    {
        this.policy.setIdleTimeout(ms);
        this.httpClient.setIdleTimeout(ms);
    }

    public void setMaxTextMessageBufferSize(int max)
    {
        this.policy.setMaxTextMessageBufferSize(max);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpThis(out);
        dump(out,indent,getOpenSessions());
    }

    public HttpClient getHttpClient()
    {
        return this.httpClient;
    }

    @Override
    public String toString()
    {
        final StringBuilder sb = new StringBuilder("WebSocketClient@");
        sb.append(Integer.toHexString(id));
        sb.append("[httpClient=").append(httpClient);
        sb.append(",openSessions.size=");
        sb.append(getOpenSessions().size());
        sb.append(']');
        return sb.toString();
    }
}
