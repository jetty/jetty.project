// ========================================================================
// Copyright (c) 2010 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.api.ExtensionConfig;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.extensions.Extension;
import org.eclipse.jetty.websocket.extensions.deflate.DeflateFrameExtension;
import org.eclipse.jetty.websocket.extensions.fragment.FragmentExtension;
import org.eclipse.jetty.websocket.extensions.identity.IdentityExtension;
import org.eclipse.jetty.websocket.server.handshake.HandshakeHixie76;
import org.eclipse.jetty.websocket.server.handshake.HandshakeRFC6455;

/**
 * Factory to create WebSocket connections
 */
public class WebSocketServerFactory extends AbstractLifeCycle
{
    private static final Logger LOG = Log.getLogger(WebSocketServerFactory.class);
    private final Queue<AsyncWebSocketConnection> connections = new ConcurrentLinkedQueue<AsyncWebSocketConnection>();

    // TODO: replace with ExtensionRegistry in websocket-core
    private final Map<String, Class<? extends Extension>> extensionClasses = new HashMap<>();
    {
        extensionClasses.put("identity",IdentityExtension.class);
        extensionClasses.put("fragment",FragmentExtension.class);
        extensionClasses.put("x-deflate-frame",DeflateFrameExtension.class);
    }

    private final Map<Integer, WebSocketServer.Handshake> handshakes = new HashMap<>();
    {
        handshakes.put(HandshakeRFC6455.VERSION,new HandshakeRFC6455());
        handshakes.put(HandshakeHixie76.VERSION,new HandshakeHixie76());
    }

    private final WebSocketServer.Acceptor acceptor;
    private final String supportedVersions;
    private WebSocketPolicy policy;

    public WebSocketServerFactory(WebSocketServer.Acceptor acceptor, WebSocketPolicy policy)
    {
        this.acceptor = acceptor;
        this.policy = policy;

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
        if ("websocket".equalsIgnoreCase(request.getHeader("Upgrade")))
        {
            String origin = request.getHeader("Origin");
            if (origin == null)
            {
                origin = request.getHeader("Sec-WebSocket-Origin");
            }
            if (!acceptor.checkOrigin(request,origin))
            {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return false;
            }

            // Try each requested protocol
            WebSocket websocket = null;

            Enumeration<String> protocols = request.getHeaders("Sec-WebSocket-Protocol");
            String protocol = null;
            while ((protocol == null) && (protocols != null) && protocols.hasMoreElements())
            {
                String candidate = protocols.nextElement();
                for (String p : parseProtocols(candidate))
                {
                    websocket = acceptor.doWebSocketConnect(request,p);
                    if (websocket != null)
                    {
                        protocol = p;
                        break;
                    }
                }
            }

            // Did we get a websocket?
            if (websocket == null)
            {
                // Try with no protocol
                websocket = acceptor.doWebSocketConnect(request,null);

                if (websocket == null)
                {
                    response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    return false;
                }
            }

            // Send the upgrade
            upgrade(request,response,websocket,protocol);
            return true;
        }

        return false;
    }

    protected boolean addConnection(AsyncWebSocketConnection connection)
    {
        return isRunning() && connections.add(connection);
    }

    protected void closeConnections()
    {
        for (AsyncWebSocketConnection connection : connections)
        {
            connection.getEndPoint().close();
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        closeConnections();
    }

    /**
     * @return A modifiable map of extension name to extension class
     */
    public Map<String, Class<? extends Extension>> getExtensionClassesMap()
    {
        return extensionClasses;
    }

    /**
     * Get the policy in use for WebSockets.
     * 
     * @return
     */
    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    public List<Extension> initExtensions(List<ExtensionConfig> requested)
    {
        List<Extension> extensions = new ArrayList<Extension>();

        for (ExtensionConfig cfg : requested)
        {
            Extension extension = newExtension(cfg.getName());

            if (extension == null)
            {
                continue;
            }

            extension.setConfig(cfg);
            LOG.debug("added {}",extension);
            extensions.add(extension);
        }
        LOG.debug("extensions={}",extensions);
        return extensions;
    }

    private Extension newExtension(String name)
    {
        try
        {
            Class<? extends Extension> extClass = extensionClasses.get(name);
            if (extClass != null)
            {
                return extClass.newInstance();
            }
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }

        return null;
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

    protected boolean removeConnection(AsyncWebSocketConnection connection)
    {
        return connections.remove(connection);
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
     * @param acceptedSubProtocol
     *            The accepted websocket sub protocol
     * @throws IOException
     *             in case of I/O errors
     */
    public void upgrade(HttpServletRequest request, HttpServletResponse response, WebSocket websocket, String acceptedSubProtocol) throws IOException
    {
        if (!"websocket".equalsIgnoreCase(request.getHeader("Upgrade")))
        {
            throw new IllegalStateException("Not a 'WebSocket: Ugprade' request");
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

        List<ExtensionConfig> extensionsRequested = new ArrayList<>();
        Enumeration<String> e = request.getHeaders("Sec-WebSocket-Extensions");
        while (e.hasMoreElements())
        {
            QuotedStringTokenizer tok = new QuotedStringTokenizer(e.nextElement(),",");
            while (tok.hasMoreTokens())
            {
                extensionsRequested.add(ExtensionConfig.parse(tok.nextToken()));
            }
        }

        WebSocketServer.Handshake handshaker = handshakes.get(version);
        if (handshaker == null)
        {
            LOG.warn("Unsupported Websocket version: " + version);
            // Per RFC 6455 - 4.4 - Supporting Multiple Versions of WebSocket Protocol
            // Using the examples as outlined
            response.setHeader("Sec-WebSocket-Version",supportedVersions);
            response.sendError(HttpStatus.BAD_REQUEST_400,"Unsupported websocket version specification");
            return;
        }

        // Create connection
        HttpConnection http = HttpConnection.getCurrentConnection();
        AsyncEndPoint endp = http.getEndPoint();
        Executor executor = http.getConnector().findExecutor();
        final AsyncWebSocketConnection connection = new AsyncWebSocketConnection(endp,executor,policy);
        endp.setAsyncConnection(connection);

        // Initialize / Negotiate Extensions
        List<Extension> extensions = initExtensions(extensionsRequested);

        // Process (version specific) handshake response
        handshaker.doHandshakeResponse(request,response,extensions,acceptedSubProtocol);

        // Add connection
        addConnection(connection);

        // Tell jetty about the new connection
        LOG.debug("Websocket upgrade {} {} {} {}",request.getRequestURI(),version,acceptedSubProtocol,connection);
        request.setAttribute("org.eclipse.jetty.io.Connection",connection); // TODO: this still needed?
    }
}
