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

package org.eclipse.jetty.websocket.server;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.api.util.QuoteUtil;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.SessionFactory;
import org.eclipse.jetty.websocket.common.SessionListener;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.WebSocketSessionFactory;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.events.EventDriverFactory;
import org.eclipse.jetty.websocket.common.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.common.extensions.WebSocketExtensionFactory;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

/**
 * Factory to create WebSocket connections
 */
public class WebSocketServerFactory extends ContainerLifeCycle implements WebSocketCreator, WebSocketServletFactory, SessionListener
{
    private static final Logger LOG = Log.getLogger(WebSocketServerFactory.class);

    private final Map<Integer, WebSocketHandshake> handshakes = new HashMap<>();
    /**
     * Have the factory maintain 1 and only 1 scheduler. All connections share this scheduler.
     */
    private final Scheduler scheduler = new ScheduledExecutorScheduler();
    private final String supportedVersions;
    private final WebSocketPolicy defaultPolicy;
    private final EventDriverFactory eventDriverFactory;
    private final ByteBufferPool bufferPool;
    private final WebSocketExtensionFactory extensionFactory;
    private List<SessionFactory> sessionFactories;
    private Set<WebSocketSession> openSessions = new CopyOnWriteArraySet<>();
    private WebSocketCreator creator;
    private List<Class<?>> registeredSocketClasses;

    public WebSocketServerFactory()
    {
        this(WebSocketPolicy.newServerPolicy(), new MappedByteBufferPool());
    }

    public WebSocketServerFactory(WebSocketPolicy policy)
    {
        this(policy, new MappedByteBufferPool());
    }

    public WebSocketServerFactory(ByteBufferPool bufferPool)
    {
        this(WebSocketPolicy.newServerPolicy(), bufferPool);
    }

    public WebSocketServerFactory(WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        handshakes.put(HandshakeRFC6455.VERSION, new HandshakeRFC6455());

        addBean(scheduler);
        addBean(bufferPool);

        this.registeredSocketClasses = new ArrayList<>();

        this.defaultPolicy = policy;
        this.eventDriverFactory = new EventDriverFactory(defaultPolicy);
        this.bufferPool = bufferPool;
        this.extensionFactory = new WebSocketExtensionFactory(defaultPolicy, this.bufferPool);
        
        // Bug #431459 - unregistering compression extensions till they are more stable
        this.extensionFactory.unregister("deflate-frame");
        this.extensionFactory.unregister("permessage-deflate");
        this.extensionFactory.unregister("x-webkit-deflate-frame");
        
        this.sessionFactories = new ArrayList<>();
        this.sessionFactories.add(new WebSocketSessionFactory(this));
        this.creator = this;

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
    }

    @Override
    public boolean acceptWebSocket(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        return acceptWebSocket(getCreator(), request, response);
    }

    @Override
    public boolean acceptWebSocket(WebSocketCreator creator, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        try
        {
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
            HttpConnection connection = (HttpConnection)request.getAttribute("org.eclipse.jetty.server.HttpConnection");
            
            // Send the upgrade
            EventDriver driver = eventDriverFactory.wrap(websocketPojo);
            return upgrade(connection, sockreq, sockresp, driver);
        }
        catch (URISyntaxException e)
        {
            throw new IOException("Unable to accept websocket due to mangled URI", e);
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

    @Override
    public void cleanup()
    {
        try
        {
            this.stop();
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }

    protected void shutdownAllConnections()
    {
        for (WebSocketSession session : openSessions)
        {
            if (session.getConnection() != null)
            {
                try
                {
                    session.getConnection().close(
                            StatusCode.SHUTDOWN,
                            "Shutdown");
                }
                catch (Throwable t)
                {
                    LOG.debug("During Shutdown All Connections",t);
                }
            }
        }
        openSessions.clear();
    }

    @Override
    public WebSocketServletFactory createFactory(WebSocketPolicy policy)
    {
        return new WebSocketServerFactory(policy, bufferPool);
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
            return firstClass.newInstance();
        }
        catch (InstantiationException | IllegalAccessException e)
        {
            throw new WebSocketException("Unable to create instance of " + firstClass, e);
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        shutdownAllConnections();
        super.doStop();
    }

    @Override
    public WebSocketCreator getCreator()
    {
        return this.creator;
    }

    public EventDriverFactory getEventDriverFactory()
    {
        return eventDriverFactory;
    }

    @Override
    public ExtensionFactory getExtensionFactory()
    {
        return extensionFactory;
    }

    public Set<WebSocketSession> getOpenSessions()
    {
        return Collections.unmodifiableSet(this.openSessions);
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return defaultPolicy;
    }

    @Override
    public void init() throws Exception
    {
        start(); // start lifecycle
    }

    @Override
    public boolean isUpgradeRequest(HttpServletRequest request, HttpServletResponse response)
    {
        if (!"GET".equalsIgnoreCase(request.getMethod()))
        {
            // not a "GET" request (not a websocket upgrade)
            return false;
        }

        String connection = request.getHeader("connection");
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

        String upgrade = request.getHeader("Upgrade");
        if (upgrade == null)
        {
            // no "Upgrade: websocket" header present.
            return false;
        }

        if (!"websocket".equalsIgnoreCase(upgrade))
        {
            LOG.debug("Not a 'Upgrade: WebSocket' (was [Upgrade: " + upgrade + "])");
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
    public void onSessionClosed(WebSocketSession session)
    {
        this.openSessions.remove(session);
    }

    @Override
    public void onSessionOpened(WebSocketSession session)
    {
        this.openSessions.add(session);
    }

    protected String[] parseProtocols(String protocol)
    {
        if (protocol == null)
        {
            return new String[]{null};
        }
        protocol = protocol.trim();
        if (protocol.length() == 0)
        {
            return new String[]{null};
        }
        String[] passed = protocol.split("\\s*,\\s*");
        String[] protocols = new String[passed.length + 1];
        System.arraycopy(passed, 0, protocols, 0, passed.length);
        return protocols;
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
     * @param http     the raw http connection
     * @param request  The request to upgrade
     * @param response The response to upgrade
     * @param driver   The websocket handler implementation to use
     * @throws IOException
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
                warn.append('"').append(ua.replaceAll("<", "&lt;")).append("\" ");
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
        Executor executor = http.getConnector().getExecutor();
        ByteBufferPool bufferPool = http.getConnector().getByteBufferPool();
        
        // Setup websocket connection
        WebSocketServerConnection wsConnection = new WebSocketServerConnection(endp, executor, scheduler, driver.getPolicy(), bufferPool);

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
        session.setPolicy(driver.getPolicy());
        session.setUpgradeRequest(request);
        // set true negotiated extension list back to response 
        response.setExtensions(extensionStack.getNegotiatedExtensions());
        session.setUpgradeResponse(response);
        wsConnection.setSession(session);

        // Setup Incoming Routing
        wsConnection.setNextIncomingFrames(extensionStack);
        extensionStack.setNextIncoming(session);

        // Setup Outgoing Routing
        session.setOutgoingHandler(extensionStack);
        extensionStack.setNextOutgoing(wsConnection);

        // Start Components
        try
        {
            session.start();
        }
        catch (Exception e)
        {
            throw new IOException("Unable to start Session", e);
        }
        try
        {
            extensionStack.start();
        }
        catch (Exception e)
        {
            throw new IOException("Unable to start Extension Stack", e);
        }

        // Tell jetty about the new upgraded connection
        request.setServletAttribute(HttpConnection.UPGRADE_CONNECTION_ATTRIBUTE, wsConnection);

        if (LOG.isDebugEnabled())
            LOG.debug("Handshake Response: {}", handshaker);

        // Process (version specific) handshake response
        handshaker.doHandshakeResponse(request, response);

        if (LOG.isDebugEnabled())
            LOG.debug("Websocket upgrade {} {} {} {}", request.getRequestURI(), version, response.getAcceptedSubProtocol(), wsConnection);

        return true;
    }
}
