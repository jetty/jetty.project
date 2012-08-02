// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================

package org.eclipse.jetty.websocket.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.Extension;
import org.eclipse.jetty.websocket.api.ExtensionRegistry;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.driver.EventMethodsCache;
import org.eclipse.jetty.websocket.driver.WebSocketEventDriver;
import org.eclipse.jetty.websocket.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.io.IncomingFrames;
import org.eclipse.jetty.websocket.io.OutgoingFrames;
import org.eclipse.jetty.websocket.io.WebSocketSession;
import org.eclipse.jetty.websocket.protocol.ExtensionConfig;
import org.eclipse.jetty.websocket.server.handshake.HandshakeHixie76;
import org.eclipse.jetty.websocket.server.handshake.HandshakeRFC6455;

/**
 * Factory to create WebSocket connections
 */
public class WebSocketServerFactory extends AbstractLifeCycle implements WebSocketCreator
{
    private static final Logger LOG = Log.getLogger(WebSocketServerFactory.class);

    private final Map<Integer, WebSocketHandshake> handshakes = new HashMap<>();
    {
        handshakes.put(HandshakeRFC6455.VERSION,new HandshakeRFC6455());
        handshakes.put(HandshakeHixie76.VERSION,new HandshakeHixie76());
    }

    private final Queue<WebSocketSession> sessions = new ConcurrentLinkedQueue<>();
    /**
     * Have the factory maintain 1 and only 1 scheduler. All connections share this scheduler.
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final String supportedVersions;
    private final WebSocketPolicy basePolicy;
    private final EventMethodsCache methodsCache;
    private final ByteBufferPool bufferPool;
    private final WebSocketExtensionRegistry extensionRegistry;
    private WebSocketCreator creator;
    private Class<?> firstRegisteredClass;

    public WebSocketServerFactory(WebSocketPolicy policy)
    {
        this(policy,new StandardByteBufferPool());
    }

    public WebSocketServerFactory(WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        this.basePolicy = policy;
        this.methodsCache = new EventMethodsCache();
        this.bufferPool = bufferPool;
        this.extensionRegistry = new WebSocketExtensionRegistry(basePolicy,bufferPool);
        this.creator = this;

        // Create supportedVersions
        List<Integer> versions = new ArrayList<>();
        for (int v : handshakes.keySet())
        {
            versions.add(v);
        }
        Collections.sort(versions,Collections.reverseOrder()); // newest first
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

    public boolean acceptWebSocket(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        ServletWebSocketRequest sockreq = new ServletWebSocketRequest(request);
        ServletWebSocketResponse sockresp = new ServletWebSocketResponse(response);

        WebSocketCreator creator = getCreator();

        Object websocketPojo = creator.createWebSocket(sockreq,sockresp);

        // Handle response forbidden (and similar paths)
        if (sockresp.isCommitted())
        {
            return false;
        }

        if (websocketPojo == null)
        {
            // no creation, sorry
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return false;
        }

        // Send the upgrade
        WebSocketPolicy objPolicy = this.basePolicy.clonePolicy();
        WebSocketEventDriver websocket = new WebSocketEventDriver(websocketPojo,methodsCache,objPolicy,bufferPool);
        return upgrade(sockreq,sockresp,websocket);
    }

    protected void closeConnections()
    {
        for (WebSocketSession session : sessions)
        {
            try
            {
                session.close();
            }
            catch (IOException e)
            {
                LOG.warn("Unable to close session",e);
            }
        }
        sessions.clear();
    }

    @Override
    public Object createWebSocket(WebSocketRequest req, WebSocketResponse resp)
    {
        if (methodsCache.count() < 1)
        {
            throw new WebSocketException("No WebSockets have been registered with the factory.  Cannot use default implementation of WebSocketCreator.");
        }

        if (methodsCache.count() > 1)
        {
            LOG.warn("You have registered more than 1 websocket object, and are using the default WebSocketCreator! Using first registered websocket.");
        }

        try
        {
            return firstRegisteredClass.newInstance();
        }
        catch (InstantiationException | IllegalAccessException e)
        {
            throw new WebSocketException("Unable to create instance of " + firstRegisteredClass,e);
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        closeConnections();
        super.doStop();
    }

    public WebSocketCreator getCreator()
    {
        return this.creator;
    }

    public ExtensionRegistry getExtensionRegistry()
    {
        return extensionRegistry;
    }

    /**
     * Get the base policy in use for WebSockets.
     * <p>
     * Note: individual WebSocket implementations can override some of the values in here by using the {@link WebSocket &#064;WebSocket} annotation.
     * 
     * @return the base policy
     */
    public WebSocketPolicy getPolicy()
    {
        return basePolicy;
    }

    public List<Extension> initExtensions(List<ExtensionConfig> requested)
    {
        List<Extension> extensions = new ArrayList<Extension>();

        for (ExtensionConfig cfg : requested)
        {
            Extension extension = extensionRegistry.newInstance(cfg);

            if (extension == null)
            {
                continue;
            }

            LOG.debug("added {}",extension);
            extensions.add(extension);
        }
        LOG.debug("extensions={}",extensions);
        return extensions;
    }

    public boolean isUpgradeRequest(HttpServletRequest request, HttpServletResponse response)
    {
        String upgrade = request.getHeader("Upgrade");
        if (upgrade == null)
        {
            // Quietly fail
            return false;
        }

        if (!"websocket".equalsIgnoreCase(upgrade))
        {
            LOG.warn("Not a 'Upgrade: WebSocket' (was [Upgrade: " + upgrade + "])");
            return false;
        }

        if (!"HTTP/1.1".equals(request.getProtocol()))
        {
            LOG.warn("Not a 'HTTP/1.1' request (was [" + request.getProtocol() + "])");
            return false;
        }

        return true;
    }

    protected String[] parseProtocols(String protocol)
    {
        if (protocol == null)
        {
            return new String[]
            { null };
        }
        protocol = protocol.trim();
        if ((protocol == null) || (protocol.length() == 0))
        {
            return new String[]
            { null };
        }
        String[] passed = protocol.split("\\s*,\\s*");
        String[] protocols = new String[passed.length + 1];
        System.arraycopy(passed,0,protocols,0,passed.length);
        return protocols;
    }

    public void register(Class<?> websocketClass)
    {
        if (firstRegisteredClass == null)
        {
            firstRegisteredClass = websocketClass;
        }
        methodsCache.register(websocketClass);
    }

    public boolean sessionClosed(WebSocketSession session)
    {
        return isRunning() && sessions.remove(session);
    }

    public boolean sessionOpened(WebSocketSession session)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Session Opened: {}",session);
        }
        if (!isRunning())
        {
            LOG.warn("Factory is not running");
            return false;
        }
        boolean ret = sessions.offer(session);
        session.onConnect();
        return ret;
    }

    public void setCreator(WebSocketCreator creator)
    {
        this.creator = creator;
    }

    /**
     * Upgrade the request/response to a WebSocket Connection.
     * <p>
     * This method will not normally return, but will instead throw a UpgradeConnectionException, to exit HTTP handling and initiate WebSocket handling of the
     * connection.
     * 
     * @param request
     *            The request to upgrade
     * @param response
     *            The response to upgrade
     * @param websocket
     *            The websocket handler implementation to use
     * @throws IOException
     *             in case of I/O errors
     */
    public boolean upgrade(ServletWebSocketRequest request, ServletWebSocketResponse response, WebSocketEventDriver websocket) throws IOException
    {
        if (!"websocket".equalsIgnoreCase(request.getHeader("Upgrade")))
        {
            throw new IllegalStateException("Not a 'WebSocket: Upgrade' request");
        }
        if (!"HTTP/1.1".equals(request.getProtocol()))
        {
            throw new IllegalStateException("Not a 'HTTP/1.1' request");
        }

        int version = request.getIntHeader("Sec-WebSocket-Version");
        if (version < 0)
        {
            // Old pre-RFC version specifications (header not present in RFC-6455)
            version = request.getIntHeader("Sec-WebSocket-Draft");
        }

        WebSocketHandshake handshaker = handshakes.get(version);
        if (handshaker == null)
        {
            LOG.warn("Unsupported Websocket version: " + version);
            // Per RFC 6455 - 4.4 - Supporting Multiple Versions of WebSocket Protocol
            // Using the examples as outlined
            response.setHeader("Sec-WebSocket-Version",supportedVersions);
            response.sendError(HttpStatus.BAD_REQUEST_400,"Unsupported websocket version specification");
            return false;
        }

        // Create connection
        HttpConnection http = HttpConnection.getCurrentConnection();
        AsyncEndPoint endp = http.getEndPoint();
        Executor executor = http.getConnector().findExecutor();
        ByteBufferPool bufferPool = http.getConnector().getByteBufferPool();
        WebSocketServerAsyncConnection connection = new WebSocketServerAsyncConnection(endp,executor,scheduler,websocket.getPolicy(),bufferPool,this);
        // Tell jetty about the new connection
        request.setAttribute(HttpConnection.UPGRADE_CONNECTION_ATTR,connection);

        LOG.debug("HttpConnection: {}",http);
        LOG.debug("AsyncWebSocketConnection: {}",connection);

        // Initialize / Negotiate Extensions
        WebSocketSession session = new WebSocketSession(websocket,connection,getPolicy(),response.getAcceptedSubProtocol());
        connection.setSession(session);
        List<Extension> extensions = initExtensions(request.getExtensions());

        // Start with default routing.
        IncomingFrames incoming = session;
        OutgoingFrames outgoing = connection;

        // Connect extensions
        if (extensions != null)
        {
            Iterator<Extension> extIter;
            // Connect outgoings
            extIter = extensions.iterator();
            while (extIter.hasNext())
            {
                Extension ext = extIter.next();
                ext.setNextOutgoingFrames(outgoing);
                outgoing = ext;

                // Handle RSV reservations
                if (ext.useRsv1())
                {
                    connection.getGenerator().setRsv1InUse(true);
                    connection.getParser().setRsv1InUse(true);
                }
                if (ext.useRsv2())
                {
                    connection.getGenerator().setRsv2InUse(true);
                    connection.getParser().setRsv2InUse(true);
                }
                if (ext.useRsv3())
                {
                    connection.getGenerator().setRsv3InUse(true);
                    connection.getParser().setRsv3InUse(true);
                }
            }

            // Connect incomings
            Collections.reverse(extensions);
            extIter = extensions.iterator();
            while (extIter.hasNext())
            {
                Extension ext = extIter.next();
                ext.setNextIncomingFrames(incoming);
                incoming = ext;
            }
        }

        // configure session for outgoing flows
        session.setOutgoing(outgoing);
        // configure connection for incoming flows
        connection.getParser().setIncomingFramesHandler(incoming);

        // Process (version specific) handshake response
        LOG.debug("Handshake Response: {}",handshaker);
        handshaker.doHandshakeResponse(request,response);

        LOG.debug("Websocket upgrade {} {} {} {}",request.getRequestURI(),version,response.getAcceptedSubProtocol(),connection);
        return true;
    }
}
