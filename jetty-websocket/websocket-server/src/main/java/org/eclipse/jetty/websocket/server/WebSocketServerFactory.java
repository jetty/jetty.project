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

package org.eclipse.jetty.websocket.server;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.DeprecationWarning;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.api.util.QuoteUtil;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.SessionFactory;
import org.eclipse.jetty.websocket.common.SessionTracker;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.WebSocketSessionFactory;
import org.eclipse.jetty.websocket.common.WebSocketSessionListener;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.events.EventDriverFactory;
import org.eclipse.jetty.websocket.common.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.common.extensions.WebSocketExtensionFactory;
import org.eclipse.jetty.websocket.common.io.AbstractWebSocketConnection;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

/**
 * Factory to create WebSocket connections
 */
public class WebSocketServerFactory extends ContainerLifeCycle implements WebSocketCreator, WebSocketContainerScope, WebSocketServletFactory
{
    private static final Logger LOG = Log.getLogger(WebSocketServerFactory.class);

    private final ClassLoader contextClassloader;
    private final Map<Integer, WebSocketHandshake> handshakes = new HashMap<>();
    // TODO: obtain shared (per server scheduler, somehow)
    private final Scheduler scheduler = new ScheduledExecutorScheduler(String.format("WebSocket-Scheduler-%x", hashCode()), false);
    private final List<WebSocketSessionListener> listeners = new ArrayList<>();
    private final String supportedVersions;
    private final WebSocketPolicy defaultPolicy;
    private final EventDriverFactory eventDriverFactory;
    private final ByteBufferPool bufferPool;
    private final WebSocketExtensionFactory extensionFactory;
    private final ServletContext context; // can be null when this factory is used from WebSocketHandler
    private final List<SessionFactory> sessionFactories = new ArrayList<>();
    private final SessionTracker sessionTracker = new SessionTracker();
    private final List<Class<?>> registeredSocketClasses = new ArrayList<>();
    private Executor executor;
    private DecoratedObjectFactory objectFactory;
    private WebSocketCreator creator;

    /**
     * Entry point for Spring Boot's MockMVC framework
     */
    public WebSocketServerFactory()
    {
        this(WebSocketPolicy.newServerPolicy(), null, new MappedByteBufferPool());
    }

    public WebSocketServerFactory(ServletContext context)
    {
        this(context, WebSocketPolicy.newServerPolicy(), new MappedByteBufferPool());
    }

    public WebSocketServerFactory(ServletContext context, ByteBufferPool bufferPool)
    {
        this(context, WebSocketPolicy.newServerPolicy(), bufferPool);
    }

    /**
     * Entry point for {@link org.eclipse.jetty.websocket.servlet.WebSocketServletFactory.Loader}
     *
     * @param context the servlet context
     * @param policy the policy to use
     */
    public WebSocketServerFactory(ServletContext context, WebSocketPolicy policy)
    {
        this(context, policy, new MappedByteBufferPool());
    }

    public WebSocketServerFactory(ServletContext context, WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        this(Objects.requireNonNull(context, ServletContext.class.getName()), policy, null, null, bufferPool);
    }

    /**
     * Protected entry point for {@link WebSocketHandler}
     *
     * @param policy the policy to use
     * @param executor the executor to use
     * @param bufferPool the buffer pool to use
     */
    protected WebSocketServerFactory(WebSocketPolicy policy, Executor executor, ByteBufferPool bufferPool)
    {
        this(null, policy, new DecoratedObjectFactory(), executor, bufferPool);
    }

    private WebSocketServerFactory(ServletContext context, WebSocketPolicy policy, DecoratedObjectFactory objectFactory, Executor executor, ByteBufferPool bufferPool)
    {
        this.context = context;
        this.defaultPolicy = policy;
        this.objectFactory = objectFactory;
        this.executor = executor;
        this.bufferPool = bufferPool;

        this.creator = this;
        this.contextClassloader = Thread.currentThread().getContextClassLoader();
        this.eventDriverFactory = new EventDriverFactory(this);
        this.extensionFactory = new WebSocketExtensionFactory(this);

        this.handshakes.put(HandshakeRFC6455.VERSION, new HandshakeRFC6455());
        this.sessionFactories.add(new WebSocketSessionFactory(this));

        // Create supportedVersions
        List<Integer> versions = new ArrayList<>();
        for (int v : handshakes.keySet())
        {
            versions.add(v);
        }
        Collections.sort(versions, Collections.reverseOrder()); // newest first
        StringBuilder rv = new StringBuilder();
        for (int v : versions)
        {
            if (rv.length() > 0)
            {
                rv.append(", ");
            }
            rv.append(v);
        }
        supportedVersions = rv.toString();

        addBean(scheduler);
        addBean(bufferPool);
        addBean(sessionTracker);
        addBean(extensionFactory);
        listeners.add(this.sessionTracker);
    }

    @Override
    public void addSessionListener(WebSocketSessionListener listener)
    {
        this.listeners.add(listener);
    }

    @Override
    public void removeSessionListener(WebSocketSessionListener listener)
    {
        this.listeners.remove(listener);
    }

    @Override
    public Collection<WebSocketSessionListener> getSessionListeners()
    {
        return this.listeners;
    }

    @Override
    public boolean acceptWebSocket(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        return acceptWebSocket(getCreator(), request, response);
    }

    @Override
    public boolean acceptWebSocket(WebSocketCreator creator, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(contextClassloader);

            // Create Servlet Specific Upgrade Request/Response objects
            ServletUpgradeRequest sockreq = new ServletUpgradeRequest(request);
            ServletUpgradeResponse sockresp = new ServletUpgradeResponse(response);

            Object websocketPojo = creator.createWebSocket(sockreq, sockresp);

            // Handle response forbidden (and similar paths)
            if (sockresp.isCommitted())
            {
                return false;
            }

            if (websocketPojo == null)
            {
                // no creation, sorry
                sockresp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Endpoint Creation Failed");
                return false;
            }

            // Allow Decorators to do their thing
            websocketPojo = getObjectFactory().decorate(websocketPojo);

            // Get the original HTTPConnection
            HttpConnection connection = (HttpConnection)request.getAttribute("org.eclipse.jetty.server.HttpConnection");

            // Send the upgrade
            EventDriver driver = eventDriverFactory.wrap(websocketPojo);
            return upgrade(connection, sockreq, sockresp, driver);
        }
        catch (URISyntaxException e)
        {
            throw new IOException("Unable to accept websocket due to mangled URI", e);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    public void addSessionFactory(SessionFactory sessionFactory)
    {
        if (sessionFactories.contains(sessionFactory))
        {
            return;
        }
        this.sessionFactories.add(sessionFactory);
    }

    private WebSocketSession createSession(URI requestURI, EventDriver websocket, LogicalConnection connection)
    {
        if (websocket == null)
        {
            throw new InvalidWebSocketException("Unable to create Session from null websocket");
        }

        for (SessionFactory impl : sessionFactories)
        {
            if (impl.supports(websocket))
            {
                try
                {
                    return impl.createSession(requestURI, websocket, connection);
                }
                catch (Throwable e)
                {
                    throw new InvalidWebSocketException("Unable to create Session", e);
                }
            }
        }

        throw new InvalidWebSocketException("Unable to create Session: unrecognized internal EventDriver type: " + websocket.getClass().getName());
    }

    /**
     * Default Creator logic
     */
    @Override
    public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
    {
        if (registeredSocketClasses.size() < 1)
        {
            throw new WebSocketException("No WebSockets have been registered with the factory.  Cannot use default implementation of WebSocketCreator.");
        }

        if (registeredSocketClasses.size() > 1)
        {
            LOG.warn("You have registered more than 1 websocket object, and are using the default WebSocketCreator! Using first registered websocket.");
        }

        Class<?> firstClass = registeredSocketClasses.get(0);
        try
        {
            return objectFactory.createInstance(firstClass);
        }
        catch (Exception e)
        {
            throw new WebSocketException("Unable to create instance of " + firstClass, e);
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        if (this.objectFactory == null)
        {
            this.objectFactory = findDecoratedObjectFactory();
        }

        if (this.executor == null)
        {
            this.executor = findExecutor();
        }

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        sessionTracker.stop();
        extensionFactory.stop();
        super.doStop();
    }

    /**
     * Attempt to find the DecoratedObjectFactory that should be used.
     *
     * @return the DecoratedObjectFactory that should be used. (never null)
     */
    private DecoratedObjectFactory findDecoratedObjectFactory()
    {
        DecoratedObjectFactory objectFactory;

        if (context != null)
        {
            objectFactory = (DecoratedObjectFactory)context.getAttribute(DecoratedObjectFactory.ATTR);
            if (objectFactory != null)
            {
                return objectFactory;
            }
        }

        objectFactory = new DecoratedObjectFactory();
        objectFactory.addDecorator(new DeprecationWarning());
        LOG.info("No DecoratedObjectFactory provided, using new {}", objectFactory);
        return objectFactory;
    }

    /**
     * Attempt to find the Executor that should be used.
     *
     * @return the Executor that should be used. (never null)
     */
    private Executor findExecutor()
    {
        // Try as bean
        Executor executor = getBean(Executor.class);
        if (executor != null)
        {
            return executor;
        }

        // Attempt to pull Executor from ServletContext attribute
        if (context != null)
        {
            // Try websocket specific one first
            Executor contextExecutor = (Executor)context.getAttribute("org.eclipse.jetty.websocket.Executor");
            if (contextExecutor != null)
            {
                return contextExecutor;
            }

            // Try ContextHandler version
            contextExecutor = (Executor)context.getAttribute("org.eclipse.jetty.server.Executor");
            if (contextExecutor != null)
            {
                return contextExecutor;
            }

            // Try Executor from Jetty Server
            ContextHandler contextHandler = ContextHandler.getContextHandler(context);
            if (contextHandler != null)
            {
                contextExecutor = contextHandler.getServer().getThreadPool();
                if (contextExecutor != null) // This should always be true!
                {
                    return contextExecutor;
                }
            }
        }

        // All else fails, Create a new one
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName("WebSocketServerFactory");
        addManaged(threadPool);
        LOG.info("No Executor provided, using new {}", threadPool);
        return threadPool;
    }

    @Override
    public ByteBufferPool getBufferPool()
    {
        return this.bufferPool;
    }

    @Override
    public WebSocketCreator getCreator()
    {
        return this.creator;
    }

    @Override
    public Executor getExecutor()
    {
        return this.executor;
    }

    @Override
    public DecoratedObjectFactory getObjectFactory()
    {
        return objectFactory;
    }

    public EventDriverFactory getEventDriverFactory()
    {
        return eventDriverFactory;
    }

    @Override
    public Set<String> getAvailableExtensionNames()
    {
        return Collections.unmodifiableSet(extensionFactory.getExtensionNames());
    }

    @Deprecated
    @Override
    public ExtensionFactory getExtensionFactory()
    {
        return extensionFactory;
    }

    public Collection<WebSocketSession> getOpenSessions()
    {
        return this.sessionTracker.getSessions();
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return defaultPolicy;
    }

    @Override
    public SslContextFactory getSslContextFactory()
    {
        /* Not relevant for a Server, as this is defined in the
         * Connector configuration
         */
        return null;
    }

    @Override
    public boolean isUpgradeRequest(HttpServletRequest request, HttpServletResponse response)
    {
        // Tests sorted by least common to most common.

        String upgrade = request.getHeader("Upgrade");
        if (upgrade == null)
        {
            // no "Upgrade: websocket" header present.
            return false;
        }

        if (!"websocket".equalsIgnoreCase(upgrade))
        {
            // Not a websocket upgrade
            return false;
        }

        String connection = request.getHeader("Connection");
        if (connection == null)
        {
            // no "Connection: upgrade" header present.
            return false;
        }

        // Test for "Upgrade" token
        boolean foundUpgradeToken = false;
        Iterator<String> iter = QuoteUtil.splitAt(connection, ",");
        while (iter.hasNext())
        {
            String token = iter.next();
            if ("upgrade".equalsIgnoreCase(token))
            {
                foundUpgradeToken = true;
                break;
            }
        }

        if (!foundUpgradeToken)
        {
            return false;
        }

        if (!"GET".equalsIgnoreCase(request.getMethod()))
        {
            // not a "GET" request (not a websocket upgrade)
            return false;
        }

        if (!"HTTP/1.1".equals(request.getProtocol()))
        {
            if ("HTTP/2".equals(request.getProtocol()))
            {
                LOG.warn("WebSocket Bootstrap from HTTP/2 (RFC8441) not supported in Jetty 9.x");
            }
            else
            {
                LOG.warn("Not a 'HTTP/1.1' request (was [" + request.getProtocol() + "])");
            }
            return false;
        }

        return true;
    }

    @Override
    public void register(Class<?> websocketPojo)
    {
        registeredSocketClasses.add(websocketPojo);
    }

    @Override
    public void setCreator(WebSocketCreator creator)
    {
        this.creator = creator;
    }

    /**
     * Upgrade the request/response to a WebSocket Connection.
     * <p/>
     * This method will not normally return, but will instead throw a UpgradeConnectionException, to exit HTTP handling and initiate WebSocket handling of the
     * connection.
     *
     * @param http the raw http connection
     * @param request The request to upgrade
     * @param response The response to upgrade
     * @param driver The websocket handler implementation to use
     */
    private boolean upgrade(HttpConnection http, ServletUpgradeRequest request, ServletUpgradeResponse response, EventDriver driver) throws IOException
    {
        if (!"websocket".equalsIgnoreCase(request.getHeader("Upgrade")))
        {
            throw new IllegalStateException("Not a 'WebSocket: Upgrade' request");
        }

        if (!"HTTP/1.1".equals(request.getHttpVersion()))
        {
            throw new IllegalStateException("Not a 'HTTP/1.1' request");
        }

        int version = request.getHeaderInt("Sec-WebSocket-Version");
        if (version < 0)
        {
            // Old pre-RFC version specifications (header not present in RFC-6455)
            version = request.getHeaderInt("Sec-WebSocket-Draft");
        }

        WebSocketHandshake handshaker = handshakes.get(version);
        if (handshaker == null)
        {
            StringBuilder warn = new StringBuilder();
            warn.append("Client ").append(request.getRemoteAddress());
            warn.append(" (:").append(request.getRemotePort());
            warn.append(") User Agent: ");
            String ua = request.getHeader("User-Agent");
            if (ua == null)
            {
                warn.append("[unset] ");
            }
            else
            {
                warn.append('"').append(StringUtil.sanitizeXmlString(ua)).append("\" ");
            }
            warn.append("requested WebSocket version [").append(version);
            warn.append("], Jetty supports version");
            if (handshakes.size() > 1)
            {
                warn.append('s');
            }
            warn.append(": [").append(supportedVersions).append("]");
            LOG.warn(warn.toString());

            // Per RFC 6455 - 4.4 - Supporting Multiple Versions of WebSocket Protocol
            // Using the examples as outlined
            response.setHeader("Sec-WebSocket-Version", supportedVersions);
            response.sendError(HttpStatus.BAD_REQUEST_400, "Unsupported websocket version specification");
            return false;
        }

        // Initialize / Negotiate Extensions
        ExtensionStack extensionStack = new ExtensionStack(getExtensionFactory());
        // The JSR allows for the extensions to be pre-negotiated, filtered, etc...
        // Usually from a Configurator.
        if (response.isExtensionsNegotiated())
        {
            // Use pre-negotiated extension list from response
            extensionStack.negotiate(response.getExtensions());
        }
        else
        {
            // Use raw extension list from request
            extensionStack.negotiate(request.getExtensions());
        }

        // Get original HTTP connection
        EndPoint endp = http.getEndPoint();
        Connector connector = http.getConnector();
        Executor executor = connector.getExecutor();
        ByteBufferPool bufferPool = connector.getByteBufferPool();

        // Setup websocket connection
        AbstractWebSocketConnection wsConnection = new WebSocketServerConnection(endp, executor, scheduler, driver.getPolicy(), bufferPool);

        for (Connection.Listener listener : connector.getBeans(Connection.Listener.class))
        {
            wsConnection.addListener(listener);
        }

        extensionStack.setPolicy(driver.getPolicy());
        extensionStack.configure(wsConnection.getParser());
        extensionStack.configure(wsConnection.getGenerator());

        if (LOG.isDebugEnabled())
        {
            LOG.debug("HttpConnection: {}", http);
            LOG.debug("WebSocketConnection: {}", wsConnection);
        }

        // Setup Session
        WebSocketSession session = createSession(request.getRequestURI(), driver, wsConnection);
        session.setUpgradeRequest(request);
        // set true negotiated extension list back to response
        response.setExtensions(extensionStack.getNegotiatedExtensions());
        session.setUpgradeResponse(response);
        wsConnection.addListener(session);

        // Setup Incoming Routing
        wsConnection.setNextIncomingFrames(extensionStack);
        extensionStack.setNextIncoming(session);

        // Setup Outgoing Routing
        session.setOutgoingHandler(extensionStack);
        extensionStack.setNextOutgoing(wsConnection);

        // Start Components
        session.addManaged(extensionStack);

        if (session.isFailed())
        {
            throw new IOException("Session failed to start");
        }

        // Tell jetty about the new upgraded connection
        request.setServletAttribute(HttpConnection.UPGRADE_CONNECTION_ATTRIBUTE, wsConnection);

        if (LOG.isDebugEnabled())
            LOG.debug("Handshake Response: {}", handshaker);

        if (getSendServerVersion(connector))
            response.setHeader("Server", HttpConfiguration.SERVER_VERSION);

        // Process (version specific) handshake response
        handshaker.doHandshakeResponse(request, response);

        response.setSuccess(true);

        if (LOG.isDebugEnabled())
            LOG.debug("Websocket upgrade {} {} {} {}", request.getRequestURI(), version, response.getAcceptedSubProtocol(), wsConnection);

        return true;
    }

    private boolean getSendServerVersion(Connector connector)
    {
        ConnectionFactory connFactory = connector.getConnectionFactory(HttpVersion.HTTP_1_1.asString());
        if (connFactory == null)
            return false;

        if (connFactory instanceof HttpConnectionFactory)
        {
            HttpConfiguration httpConf = ((HttpConnectionFactory)connFactory).getHttpConfiguration();
            if (httpConf != null)
                return httpConf.getSendServerVersion();
        }
        return false;
    }

    @Override
    public String toString()
    {
        final StringBuilder sb = new StringBuilder(this.getClass().getSimpleName());
        sb.append('@').append(Integer.toHexString(hashCode()));
        sb.append("[defaultPolicy=").append(defaultPolicy);
        sb.append(",creator=").append(creator.getClass().getName());
        sb.append("]");
        return sb.toString();
    }
}
