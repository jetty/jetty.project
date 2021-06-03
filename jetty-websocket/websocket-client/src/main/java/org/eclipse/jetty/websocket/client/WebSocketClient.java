//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.ShutdownThread;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.client.io.ConnectionManager;
import org.eclipse.jetty.websocket.client.io.UpgradeListener;
import org.eclipse.jetty.websocket.client.masks.Masker;
import org.eclipse.jetty.websocket.client.masks.RandomMasker;
import org.eclipse.jetty.websocket.common.SessionFactory;
import org.eclipse.jetty.websocket.common.SessionTracker;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.WebSocketSessionFactory;
import org.eclipse.jetty.websocket.common.WebSocketSessionListener;
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

    // CDI layer
    private final Supplier<DecoratedObjectFactory> objectFactorySupplier;

    // WebSocket Specifics
    private final ClassLoader classloader;
    private final WebSocketPolicy policy;
    private final WebSocketExtensionFactory extensionRegistry;
    private final SessionTracker sessionTracker = new SessionTracker();
    private final List<WebSocketSessionListener> sessionListeners = new ArrayList<>();
    private EventDriverFactory eventDriverFactory;
    private SessionFactory sessionFactory;

    // defaults to true for backwards compatibility
    private boolean stopAtShutdown = true;

    /**
     * Instantiate a WebSocketClient with defaults.
     */
    public WebSocketClient()
    {
        this(HttpClientProvider.get(null), null);
    }

    /**
     * Instantiate a WebSocketClient using provided HttpClient.
     *
     * @param httpClient the HttpClient to use for WebSocketClient.
     */
    public WebSocketClient(HttpClient httpClient)
    {
        this(httpClient, null);
    }

    /**
     * Instantiate a WebSocketClient using HttpClient for defaults
     *
     * @param httpClient the HttpClient that underlying WebSocket client uses
     * @param decoratedObjectFactory the DecoratedObjectFactory for all client instantiated classes
     */
    public WebSocketClient(HttpClient httpClient, DecoratedObjectFactory decoratedObjectFactory)
    {
        this.classloader = Thread.currentThread().getContextClassLoader();
        this.httpClient = Objects.requireNonNull(httpClient, "HttpClient");

        addBean(httpClient);
        addBean(sessionTracker);
        addSessionListener(sessionTracker);

        // Always a pristine Client policy
        this.policy = WebSocketPolicy.newClientPolicy();
        // We do not support late binding of DecoratedObjectFactory in this WebSocketClient
        DecoratedObjectFactory objectFactory = decoratedObjectFactory == null ? new DecoratedObjectFactory() : decoratedObjectFactory;
        this.objectFactorySupplier = () -> objectFactory;

        this.extensionRegistry = new WebSocketExtensionFactory(this);
        addBean(extensionRegistry);

        this.eventDriverFactory = new EventDriverFactory(this);
        this.sessionFactory = new WebSocketSessionFactory(this);
    }

    /**
     * Create a new WebSocketClient
     *
     * @param sslContextFactory ssl context factory to use on the internal {@link HttpClient}
     * @deprecated use {@link #WebSocketClient(HttpClient)} instead
     */
    @Deprecated
    public WebSocketClient(SslContextFactory sslContextFactory)
    {
        this(newHttpClient(sslContextFactory, null, null), null);
    }

    /**
     * Create a new WebSocketClient
     *
     * @param executor the executor to use on the internal {@link HttpClient}
     * @deprecated use {@link #WebSocketClient(HttpClient)} instead
     */
    @Deprecated
    public WebSocketClient(Executor executor)
    {
        this(newHttpClient(null, executor, null), null);
    }

    /**
     * Create a new WebSocketClient
     *
     * @param bufferPool byte buffer pool to use on the internal {@link HttpClient}
     * @deprecated use {@link #WebSocketClient(HttpClient)} instead
     */
    @Deprecated
    public WebSocketClient(ByteBufferPool bufferPool)
    {
        this(newHttpClient(null, null, bufferPool), null);
    }

    /**
     * Create a new WebSocketClient
     *
     * @param sslContextFactory ssl context factory to use on the internal {@link HttpClient}
     * @param executor the executor to use on the internal {@link HttpClient}
     * @deprecated use {@link #WebSocketClient(HttpClient)} instead
     */
    @Deprecated
    public WebSocketClient(SslContextFactory sslContextFactory, Executor executor)
    {
        this(newHttpClient(sslContextFactory, executor, null), null);
    }

    /**
     * Create WebSocketClient other Container Scope, to allow sharing of
     * internal features like Executor, ByteBufferPool, SSLContextFactory, etc.
     *
     * @param scope the Container Scope
     * @deprecated use {@link #WebSocketClient(HttpClient)} instead
     */
    @Deprecated
    public WebSocketClient(WebSocketContainerScope scope)
    {
        this(newHttpClient(scope.getSslContextFactory(), scope.getExecutor(), scope.getBufferPool()), scope.getObjectFactory());
    }

    /**
     * Create WebSocketClient other Container Scope, to allow sharing of
     * internal features like Executor, ByteBufferPool, SSLContextFactory, etc.
     *
     * @param scope the Container Scope
     * @param sslContextFactory SSL ContextFactory to use in preference to one from
     * {@link WebSocketContainerScope#getSslContextFactory()}
     * @deprecated use {@link #WebSocketClient(HttpClient)} instead
     */
    @Deprecated
    public WebSocketClient(WebSocketContainerScope scope, SslContextFactory sslContextFactory)
    {
        /* This is constructor is in an awful place, it's got a signature that has a scope,
         * a concept that javax.websocket ServerContainer uses to share its buffer pools / executors / etc
         * with the underlying HttpClient.
         * This means that the constructor should go through the HttpClientProvider.get(scope) behaviors.
         * but it also has an arbitrary SslContextFactory parameter, which isn't in the scope that
         * HttpClientProvider uses.
         * Since this isn't used by Jetty's implementation of the javax.websocket ServerContainer
         * this behavior has been changed to be non-scoped so as to be able to use the provided
         * SslContextFactory for the underlying HttpClient instance.
         */
        this(newHttpClient(sslContextFactory, scope.getExecutor(), scope.getBufferPool()), scope.getObjectFactory());
    }

    /**
     * Create WebSocketClient using sharing instances of SSLContextFactory
     * Executor, and ByteBufferPool
     *
     * @param sslContextFactory shared SSL ContextFactory
     * @param executor shared Executor
     * @param bufferPool shared ByteBufferPool
     * @deprecated use {@link #WebSocketClient(HttpClient)} instead
     */
    @Deprecated
    public WebSocketClient(SslContextFactory sslContextFactory, Executor executor, ByteBufferPool bufferPool)
    {
        this(newHttpClient(sslContextFactory, executor, bufferPool), null);
    }

    /**
     * Create WebSocketClient based on pre-existing Container Scope, to allow sharing of
     * internal features like Executor, ByteBufferPool, SSLContextFactory, etc.
     *
     * @param scope the Container Scope
     * @param eventDriverFactory the EventDriver Factory to use
     * @param sessionFactory the SessionFactory to use
     * @deprecated use {@link WebSocketClient#WebSocketClient(WebSocketContainerScope, EventDriverFactory, SessionFactory, HttpClient)} instead
     */
    @Deprecated
    public WebSocketClient(final WebSocketContainerScope scope, EventDriverFactory eventDriverFactory, SessionFactory sessionFactory)
    {
        /* Nothing in Jetty uses this constructor anymore.
         * It's left in for backwards compat reasons.
         */
        this(scope, eventDriverFactory, sessionFactory, HttpClientProvider.get(scope));
    }

    /**
     * Create WebSocketClient based on pre-existing Container Scope, to allow sharing of
     * internal features like Executor, ByteBufferPool, SSLContextFactory, etc.
     *
     * @param scope the Container Scope
     * @param eventDriverFactory the EventDriver Factory to use
     * @param sessionFactory the SessionFactory to use
     * @param httpClient the httpClient to use
     */
    public WebSocketClient(final WebSocketContainerScope scope, EventDriverFactory eventDriverFactory, SessionFactory sessionFactory, HttpClient httpClient)
    {
        this.classloader = Thread.currentThread().getContextClassLoader();
        this.httpClient = httpClient == null ? HttpClientProvider.get(scope) : httpClient;
        addBean(this.httpClient);

        addBean(sessionTracker);
        addSessionListener(sessionTracker);

        // Ensure we get a Client version of the policy.
        this.policy = scope.getPolicy().delegateAs(WebSocketBehavior.CLIENT);

        // Support Late Binding of DecoratedObjectFactory (that CDI establishes in its own servlet context listeners)
        this.objectFactorySupplier = scope::getObjectFactory;

        this.extensionRegistry = new WebSocketExtensionFactory(this);
        addBean(extensionRegistry);

        this.eventDriverFactory = eventDriverFactory == null ? new EventDriverFactory(this) : eventDriverFactory;
        this.sessionFactory = sessionFactory == null ? new WebSocketSessionFactory(this) : sessionFactory;
    }

    private static HttpClient newHttpClient(SslContextFactory sslContextFactory, Executor executor, ByteBufferPool bufferPool)
    {
        HttpClient httpClient = new HttpClient(sslContextFactory);
        httpClient.setExecutor(executor);
        httpClient.setByteBufferPool(bufferPool);
        return httpClient;
    }

    public Future<Session> connect(Object websocket, URI toUri) throws IOException
    {
        ClientUpgradeRequest request = new ClientUpgradeRequest(toUri);
        request.setRequestURI(toUri);
        request.setLocalEndpoint(websocket);

        return connect(websocket, toUri, request);
    }

    /**
     * Connect to remote websocket endpoint
     *
     * @param websocket the websocket object
     * @param toUri the websocket uri to connect to
     * @param request the upgrade request information
     * @return the future for the session, available on success of connect
     * @throws IOException if unable to connect
     */
    public Future<Session> connect(Object websocket, URI toUri, ClientUpgradeRequest request) throws IOException
    {
        return connect(websocket, toUri, request, null);
    }

    /**
     * Connect to remote websocket endpoint
     *
     * @param websocket the websocket object
     * @param toUri the websocket uri to connect to
     * @param request the upgrade request information
     * @param upgradeListener the upgrade listener
     * @return the future for the session, available on success of connect
     * @throws IOException if unable to connect
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
        if ((!"ws".equals(scheme)) && (!"wss".equals(scheme)))
        {
            throw new IllegalArgumentException("WebSocket URI scheme only supports [ws] and [wss], not [" + scheme + "]");
        }

        if ("wss".equals(scheme))
        {
            // test for ssl context
            if (httpClient.getSslContextFactory() == null)
            {
                throw new IllegalStateException("HttpClient has no SslContextFactory, wss:// URI's are not supported in this configuration");
            }
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
            LOG.debug("connect websocket {} to {}", websocket, toUri);

        init();

        WebSocketUpgradeRequest wsReq = new WebSocketUpgradeRequest(this, httpClient, request);
        wsReq.timeout(request.getTimeout(), TimeUnit.MILLISECONDS);
        wsReq.setUpgradeListener(upgradeListener);
        for (Request.Listener l : getBeans(Request.Listener.class))
        {
            wsReq.listener(l);
        }

        return wsReq.sendAsync();
    }

    @Override
    public ClassLoader getClassLoader()
    {
        return classloader;
    }

    public void setEventDriverFactory(EventDriverFactory eventDriverFactory)
    {
        this.eventDriverFactory = eventDriverFactory;
    }

    public void setSessionFactory(SessionFactory sessionFactory)
    {
        this.sessionFactory = sessionFactory;
    }

    @Override
    protected void doStart() throws Exception
    {
        Objects.requireNonNull(httpClient, "Provided HttpClient is null");

        super.doStart();

        if (!httpClient.isRunning())
            throw new IllegalStateException("HttpClient is not running (did you forget to start it?): " + httpClient);
    }

    @Override
    protected void doStop() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Stopping {}", this);

        ShutdownThread.deregister(this);

        sessionTracker.stop();
        extensionRegistry.stop();
        super.doStop();

        if (LOG.isDebugEnabled())
            LOG.debug("Stopped {}", this);
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
    @Deprecated
    public long getAsyncWriteTimeout()
    {
        return getPolicy().getAsyncWriteTimeout();
    }

    public SocketAddress getBindAddress()
    {
        return httpClient.getBindAddress();
    }

    @Override
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

    @Override
    public Executor getExecutor()
    {
        return httpClient.getExecutor();
    }

    public ExtensionFactory getExtensionFactory()
    {
        return extensionRegistry;
    }

    /**
     * @return a {@link RandomMasker} instance
     * @deprecated not used, no replacement
     */
    @Deprecated
    public Masker getMasker()
    {
        return new RandomMasker();
    }

    /**
     * Get the maximum size for buffering of a binary message.
     *
     * @return the maximum size of a binary message buffer.
     */
    public int getMaxBinaryMessageBufferSize()
    {
        return getPolicy().getMaxBinaryMessageBufferSize();
    }

    /**
     * Get the maximum size for a binary message.
     *
     * @return the maximum size of a binary message.
     */
    public long getMaxBinaryMessageSize()
    {
        return getPolicy().getMaxBinaryMessageSize();
    }

    /**
     * Get the max idle timeout for new connections.
     *
     * @return the max idle timeout in milliseconds for new connections.
     */
    public long getMaxIdleTimeout()
    {
        return getPolicy().getIdleTimeout();
    }

    /**
     * Get the maximum size for buffering of a text message.
     *
     * @return the maximum size of a text message buffer.
     */
    public int getMaxTextMessageBufferSize()
    {
        return getPolicy().getMaxTextMessageBufferSize();
    }

    /**
     * Get the maximum size for a text message.
     *
     * @return the maximum size of a text message.
     */
    public long getMaxTextMessageSize()
    {
        return getPolicy().getMaxTextMessageSize();
    }

    @Override
    public DecoratedObjectFactory getObjectFactory()
    {
        return this.objectFactorySupplier.get();
    }

    public Set<WebSocketSession> getOpenSessions()
    {
        return sessionTracker.getSessions();
    }

    @Override
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
     * Get the in use {@link SslContextFactory}
     *
     * @return the {@link SslContextFactory} that manages TLS encryption on the internal {@link HttpClient}
     * @see #WebSocketClient(SslContextFactory)
     */
    @Override
    public SslContextFactory getSslContextFactory()
    {
        return httpClient.getSslContextFactory();
    }

    @Override
    public void addSessionListener(WebSocketSessionListener listener)
    {
        this.sessionListeners.add(listener);
    }

    @Override
    public void removeSessionListener(WebSocketSessionListener listener)
    {
        this.sessionListeners.remove(listener);
    }

    @Override
    public Collection<WebSocketSessionListener> getSessionListeners()
    {
        return this.sessionListeners;
    }

    private synchronized void init()
    {
        if (isStopAtShutdown() && !ShutdownThread.isRegistered(this))
        {
            ShutdownThread.register(this);
        }
    }

    /**
     * Factory method for new ConnectionManager
     *
     * @return the ConnectionManager instance to use
     * @deprecated has no replacement
     */
    @Deprecated
    protected ConnectionManager newConnectionManager()
    {
        throw new UnsupportedOperationException("ConnectionManager is no longer supported");
    }

    public void setAsyncWriteTimeout(long ms)
    {
        getPolicy().setAsyncWriteTimeout(ms);
    }

    /**
     * @param bindAddress the address to bind to the internal {@link HttpClient}
     * @deprecated (this is a bad bad bad typo, it has 3 { @ code " d " } characters in a row) use {@link HttpClient#setBindAddress(SocketAddress)}
     * to the internal {@link #WebSocketClient(HttpClient)}
     */
    @Deprecated
    public void setBindAdddress(SocketAddress bindAddress)
    {
        setBindAddress(bindAddress);
    }

    /**
     * Sets the Bind Address on the internal {@link HttpClient}.
     *
     * @param bindAddress the local bind address for the internal {@link HttpClient}
     */
    public void setBindAddress(SocketAddress bindAddress)
    {
        this.httpClient.setBindAddress(bindAddress);
    }

    /**
     * Set's the Bind Address on the internal {@link HttpClient}.
     *
     * @param bufferPool The buffer pool
     */
    public void setBufferPool(ByteBufferPool bufferPool)
    {
        this.httpClient.setByteBufferPool(bufferPool);
    }

    /**
     * Set the timeout for connecting to the remote server on the internal {@link HttpClient}
     *
     * @param ms the timeout in milliseconds
     */
    public void setConnectTimeout(long ms)
    {
        this.httpClient.setConnectTimeout(ms);
    }

    /**
     * Set the {@link CookieStore} to use on the internal {@link HttpClient}
     *
     * @param cookieStore The cookie store
     */
    public void setCookieStore(CookieStore cookieStore)
    {
        this.httpClient.setCookieStore(cookieStore);
    }

    /**
     * @param daemon do nothing
     * @deprecated not used, configure threading in {@link HttpClient} instead
     */
    @Deprecated
    public void setDaemon(boolean daemon)
    {
        // do nothing
    }

    /**
     * @param dispatchIO true to have IO operations be dispatched to Executor
     * @deprecated no longer used, this has no replacement
     */
    @Deprecated
    public void setDispatchIO(boolean dispatchIO)
    {
        this.httpClient.setDispatchIO(dispatchIO);
    }

    /**
     * Sets the Executor in use on the internal {@link HttpClient}
     *
     * @param executor The executor to use
     */
    public void setExecutor(Executor executor)
    {
        this.httpClient.setExecutor(executor);
    }

    /**
     * @param masker does nothing
     * @deprecated not used, no replacement
     */
    @Deprecated
    public void setMasker(Masker masker)
    {
        /* do nothing */
    }

    public void setMaxBinaryMessageBufferSize(int max)
    {
        getPolicy().setMaxBinaryMessageBufferSize(max);
    }

    /**
     * Set the max idle timeout for new connections.
     * <p>
     * Existing connections will not have their max idle timeout adjusted.
     *
     * @param ms the timeout in milliseconds
     */
    public void setMaxIdleTimeout(long ms)
    {
        getPolicy().setIdleTimeout(ms);
        this.httpClient.setIdleTimeout(ms);
    }

    public void setMaxTextMessageBufferSize(int max)
    {
        getPolicy().setMaxTextMessageBufferSize(max);
    }

    /**
     * Get the internal {@link HttpClient}.
     * <p>
     * Note: this can result in a {@link LinkageError} if used within a WebApp that runs
     * on a server that also has {@link HttpClient} on the server classpath.
     * </p>
     *
     * @return the internal {@link HttpClient}
     */
    public HttpClient getHttpClient()
    {
        return this.httpClient;
    }

    /**
     * Set JVM shutdown behavior.
     *
     * @param stop If true, this client instance will be explicitly stopped when the
     * JVM is shutdown. Otherwise the application is responsible for maintaining the WebSocketClient lifecycle.
     * @see Runtime#addShutdownHook(Thread)
     * @see ShutdownThread
     */
    public synchronized void setStopAtShutdown(boolean stop)
    {
        if (stop)
        {
            if (!stopAtShutdown && isStarted() && !ShutdownThread.isRegistered(this))
                ShutdownThread.register(this);
        }
        else
            ShutdownThread.deregister(this);

        stopAtShutdown = stop;
    }

    public boolean isStopAtShutdown()
    {
        return stopAtShutdown;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (!(o instanceof WebSocketClient))
            return false;
        WebSocketClient that = (WebSocketClient)o;
        return Objects.equals(this.httpClient, that.httpClient) &&
            Objects.equals(this.policy, that.policy);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(httpClient, policy);
    }

    @Override
    public String toString()
    {
        final StringBuilder sb = new StringBuilder("WebSocketClient@");
        sb.append(Integer.toHexString(hashCode()));
        sb.append("[httpClient=").append(httpClient);
        sb.append(",openSessions.size=");
        sb.append(getOpenSessions().size());
        sb.append(']');
        return sb.toString();
    }
}
