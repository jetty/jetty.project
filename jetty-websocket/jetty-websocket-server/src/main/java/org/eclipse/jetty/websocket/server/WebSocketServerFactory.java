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

package org.eclipse.jetty.websocket.server;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.common.LocalEndpointFactory;
import org.eclipse.jetty.websocket.common.LocalEndpointImpl;
import org.eclipse.jetty.websocket.common.RemoteEndpointImpl;
import org.eclipse.jetty.websocket.common.SessionListener;
import org.eclipse.jetty.websocket.common.WebSocketSessionImpl;
import org.eclipse.jetty.websocket.core.InvalidWebSocketException;
import org.eclipse.jetty.websocket.core.WebSocketException;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.util.QuoteUtil;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

/**
 * Factory to create WebSocket connections
 */
public class WebSocketServerFactory extends ContainerLifeCycle implements WebSocketCreator, WebSocketServletFactory, SessionListener
{
    private static final Logger LOG = Log.getLogger(WebSocketServerFactory.class);
    
    private final ClassLoader contextClassloader;
    private final Map<Integer, WebSocketHandshake> handshakes = new HashMap<>();
    private final Scheduler scheduler = new ScheduledExecutorScheduler();
    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private final String supportedVersions;
    private final WebSocketPolicy containerPolicy;
    private final ByteBufferPool bufferPool;
    private final WebSocketExtensionRegistry extensionRegistry;
    private final LocalEndpointFactory localEndpointFactory;
    private final ServletContext context; // can be null when this factory is used from WebSocketHandler
    private final List<Class<?>> registeredSocketClasses = new ArrayList<>();
    private Executor executor;
    private DecoratedObjectFactory objectFactory;
    private WebSocketCreator creator;
    private Function<WebSocketServerConnection, WebSocketSessionImpl<
            ? extends WebSocketServerFactory,
            ? extends WebSocketServerConnection,
            ? extends LocalEndpointImpl,
            ? extends RemoteEndpointImpl
            >> newSessionFunction =
            (connection) -> new WebSocketSessionImpl(WebSocketServerFactory.this, connection);
    
    public WebSocketServerFactory(ServletContext context)
    {
        this(context, WebSocketPolicy.newServerPolicy(), new MappedByteBufferPool());
    }
    
    public WebSocketServerFactory(ServletContext context, ByteBufferPool bufferPool)
    {
        this(context, WebSocketPolicy.newServerPolicy(), bufferPool);
    }
    
    public WebSocketServerFactory(ServletContext context, WebSocketPolicy policy)
    {
        this(context, policy, new MappedByteBufferPool());
    }
    
    public WebSocketServerFactory(ServletContext context, WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        this(Objects.requireNonNull(context, ServletContext.class.getName()), policy, null, null, bufferPool);
    }
    
    /**
     * Contextless WebSocketServerFactory
     *
     * @param policy the policy to use
     * @param executor the executor to use
     * @param bufferPool the buffer pool to use
     */
    public WebSocketServerFactory(WebSocketPolicy policy, Executor executor, ByteBufferPool bufferPool)
    {
        this(null, policy, new DecoratedObjectFactory(), executor, bufferPool);
    }
    
    private WebSocketServerFactory(ServletContext context, WebSocketPolicy policy, DecoratedObjectFactory objectFactory, Executor executor, ByteBufferPool bufferPool)
    {
        this.context = context;
        this.containerPolicy = policy;
        this.objectFactory = objectFactory;
        this.executor = executor;
        this.bufferPool = bufferPool;

        this.creator = this; // TODO: unset creator?
        this.contextClassloader = Thread.currentThread().getContextClassLoader();
        this.extensionRegistry = new WebSocketExtensionRegistry();
        this.localEndpointFactory = new LocalEndpointFactory();

        this.handshakes.put(HandshakeRFC6455.VERSION, new HandshakeRFC6455());

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
    }

    public void addSessionListener(SessionListener listener)
    {
        listeners.add(listener);
    }

    public boolean removeSessionListener(SessionListener listener)
    {
        return listeners.remove(listener);
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
            
            // Get the original HTTPConnection
            HttpConnection connection = (HttpConnection) request.getAttribute("org.eclipse.jetty.server.HttpConnection");
            
            // Send the upgrade
            return upgrade(connection, sockreq, sockresp, websocketPojo);
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
    
    private WebSocketSessionImpl createSession(Object websocket, WebSocketServerConnection connection)
    {
        if (websocket == null)
        {
            throw new InvalidWebSocketException("Unable to create Session from null websocket");
        }

        WebSocketSessionImpl session = newSessionFunction.apply(connection);
        LocalEndpointImpl localEndpoint = localEndpointFactory.createLocalEndpoint(websocket, session, getPolicy(), getExecutor());
        RemoteEndpointImpl remoteEndpoint = new RemoteEndpointImpl(connection, connection.getRemoteAddress());

        session.setWebSocketEndpoint(websocket, localEndpoint.getPolicy(), localEndpoint, remoteEndpoint);
        return session;
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
        catch (InstantiationException | IllegalAccessException e)
        {
            throw new WebSocketException("Unable to create instance of " + firstClass, e);
        }
    }
    
    @Override
    protected void doStart() throws Exception
    {
        if(this.objectFactory == null && context != null)
        {
            this.objectFactory = (DecoratedObjectFactory) context.getAttribute(DecoratedObjectFactory.ATTR);
            if (this.objectFactory == null)
            {
                throw new IllegalStateException("Unable to find required ServletContext attribute: " + DecoratedObjectFactory.ATTR);
            }
        }
    
        if(this.executor == null && context != null)
        {
            ContextHandler contextHandler = ContextHandler.getContextHandler(context);
            this.executor = contextHandler.getServer().getThreadPool();
        }
        
        Objects.requireNonNull(this.objectFactory, DecoratedObjectFactory.class.getName());
        Objects.requireNonNull(this.executor, Executor.class.getName());
        
        super.doStart();
    }
    
    public ByteBufferPool getBufferPool()
    {
        return this.bufferPool;
    }
    
    @Override
    public WebSocketCreator getCreator()
    {
        return this.creator;
    }
    
    public Executor getExecutor()
    {
        return this.executor;
    }
    
    public DecoratedObjectFactory getObjectFactory()
    {
        return objectFactory;
    }

    @Override
    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return extensionRegistry;
    }
    
    public Collection<WebSocketSessionImpl> getOpenSessions()
    {
        return getBeans(WebSocketSessionImpl.class);
    }
    
    @Override
    public WebSocketPolicy getPolicy()
    {
        return containerPolicy;
    }
    
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
            LOG.debug("Not a 'HTTP/1.1' request (was [" + request.getProtocol() + "])");
            return false;
        }
        
        return true;
    }


    @Override
    public void onCreated(WebSocketSessionImpl session)
    {
        // TODO
    }

    @Override
    public void onOpened(WebSocketSessionImpl session)
    {
        addManaged(session);
        notifySessionListeners(listener -> listener.onOpened(session));
    }

    @Override
    public void onClosed(WebSocketSessionImpl session)
    {
        removeBean(session);
        notifySessionListeners(listener -> listener.onClosed(session));
    }
    
    private void notifySessionListeners(Consumer<SessionListener> consumer)
    {
        for (SessionListener listener : listeners)
        {
            try
            {
                consumer.accept(listener);
            }
            catch (Throwable x)
            {
                LOG.info("Exception while invoking listener " + listener, x);
            }
        }
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

    public void setNewSessionFunction(Function<WebSocketServerConnection,
            WebSocketSessionImpl<
                    ? extends WebSocketServerFactory,
                    ? extends WebSocketServerConnection,
                    ? extends LocalEndpointImpl,
                    ? extends RemoteEndpointImpl>> newSessionFunction)
    {
        this.newSessionFunction = newSessionFunction;
    }

    /**
     * Upgrade the request/response to a WebSocket Connection.
     * <p>
     * This method will not normally return, but will instead throw a UpgradeConnectionException, to exit HTTP handling and initiate WebSocket handling of the
     * connection.
     * </p>
     *
     * @param http the raw http connection
     * @param request The request to upgrade
     * @param response The response to upgrade
     */
    private boolean upgrade(HttpConnection http, ServletUpgradeRequest request, ServletUpgradeResponse response, Object websocket) throws IOException
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
        ExtensionStack extensionStack = new ExtensionStack(getExtensionRegistry());
        // The JSR allows for the extensions to be pre-negotiated, filtered, etc...
        // Usually from a Configurator.
        if (response.isExtensionsNegotiated())
        {
            // Use pre-negotiated extension list from response
            extensionStack.negotiate(objectFactory, containerPolicy, bufferPool, response.getExtensions());
        }
        else
        {
            // Use raw extension list from request
            extensionStack.negotiate(objectFactory, containerPolicy, bufferPool, request.getExtensions());
        }
        
        // Get original HTTP connection
        EndPoint endp = http.getEndPoint();
        Connector connector = http.getConnector();
        Executor executor = connector.getExecutor();
        ByteBufferPool bufferPool = connector.getByteBufferPool();
        
        // Setup websocket connection
        WebSocketServerConnection wsConnection = new WebSocketServerConnection(
                endp,
                executor,
                bufferPool,
                objectFactory,
                getPolicy().clonePolicy(),
                extensionStack,
                request,
                response);

        if (LOG.isDebugEnabled())
        {
            LOG.debug("HttpConnection: {}", http);
            LOG.debug("WSConnection: {}", wsConnection);
        }
        
        // Setup Session
        WebSocketSessionImpl session = createSession(websocket, wsConnection);
        // set true negotiated extension list back to response
        response.setExtensions(extensionStack.getNegotiatedExtensions());

        session.addManaged(extensionStack);

        // Start components
        this.addManaged(session);

        if (session.isFailed())
        {
            throw new IOException("Session failed to start");
        }
        
        // Tell jetty about the new upgraded connection
        request.setServletAttribute(HttpConnection.UPGRADE_CONNECTION_ATTRIBUTE, wsConnection);
        
        if (getSendServerVersion(connector))
            response.setHeader("Server", HttpConfiguration.SERVER_VERSION);
        
        // Process (version specific) handshake response
        handshaker.doHandshakeResponse(request, response);

        if (LOG.isDebugEnabled())
            LOG.debug("Websocket upgrade {} v={} subprotocol={} connection={}", request.getRequestURI(), version, response.getAcceptedSubProtocol(), wsConnection);
        
        return true;
    }
    
    private boolean getSendServerVersion(Connector connector)
    {
        ConnectionFactory connFactory = connector.getConnectionFactory(HttpVersion.HTTP_1_1.asString());
        if (connFactory == null)
            return false;
        
        if (connFactory instanceof HttpConnectionFactory)
        {
            HttpConfiguration httpConf = ((HttpConnectionFactory) connFactory).getHttpConfiguration();
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
        sb.append("[defaultPolicy=").append(containerPolicy);
        sb.append(",creator=").append(creator.getClass().getName());
        sb.append("]");
        return sb.toString();
    }
}
