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

package org.eclipse.jetty.websocket;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.server.AbstractHttpConnection;
import org.eclipse.jetty.server.BlockingHttpConnection;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Factory to create WebSocket connections
 */
public class WebSocketFactory extends AbstractLifeCycle
{
    private static final Logger LOG = Log.getLogger(WebSocketFactory.class);
    private final Queue<WebSocketServletConnection> connections = new ConcurrentLinkedQueue<WebSocketServletConnection>();

    public interface Acceptor
    {
        /* ------------------------------------------------------------ */
        /**
         * <p>Factory method that applications needs to implement to return a
         * {@link WebSocket} object.</p>
         * @param request the incoming HTTP upgrade request
         * @param protocol the websocket sub protocol
         * @return a new {@link WebSocket} object that will handle websocket events.
         */
        WebSocket doWebSocketConnect(HttpServletRequest request, String protocol);

        /* ------------------------------------------------------------ */
        /**
         * <p>Checks the origin of an incoming WebSocket handshake request.</p>
         * @param request the incoming HTTP upgrade request
         * @param origin the origin URI
         * @return boolean to indicate that the origin is acceptable.
         */
        boolean checkOrigin(HttpServletRequest request, String origin);
    }

    private final Map<String,Class<? extends Extension>> _extensionClasses = new HashMap<String, Class<? extends Extension>>();
    {
        _extensionClasses.put("identity",IdentityExtension.class);
        _extensionClasses.put("fragment",FragmentExtension.class);
        _extensionClasses.put("x-deflate-frame",DeflateFrameExtension.class);
    }

    private final Acceptor _acceptor;
    private WebSocketBuffers _buffers;
    private int _maxIdleTime = 300000;
    private int _maxTextMessageSize = 16 * 1024;
    private int _maxBinaryMessageSize = -1;
    private int _minVersion;

    public WebSocketFactory(Acceptor acceptor)
    {
        this(acceptor, 64 * 1024, WebSocketConnectionRFC6455.VERSION);
    }

    public WebSocketFactory(Acceptor acceptor, int bufferSize)
    {
        this(acceptor, bufferSize, WebSocketConnectionRFC6455.VERSION);
    }

    public WebSocketFactory(Acceptor acceptor, int bufferSize, int minVersion)
    {
        _buffers = new WebSocketBuffers(bufferSize);
        _acceptor = acceptor;
        _minVersion=WebSocketConnectionRFC6455.VERSION;
    }

    public int getMinVersion()
    {
        return _minVersion;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param minVersion The minimum support version (default RCF6455.VERSION == 13 )
     */
    public void setMinVersion(int minVersion)
    {
        _minVersion = minVersion;
    }

    /**
     * @return A modifiable map of extension name to extension class
     */
    public Map<String,Class<? extends Extension>> getExtensionClassesMap()
    {
        return _extensionClasses;
    }

    /**
     * Get the maxIdleTime.
     *
     * @return the maxIdleTime
     */
    public long getMaxIdleTime()
    {
        return _maxIdleTime;
    }

    /**
     * Set the maxIdleTime.
     *
     * @param maxIdleTime the maxIdleTime to set
     */
    public void setMaxIdleTime(int maxIdleTime)
    {
        _maxIdleTime = maxIdleTime;
    }

    /**
     * Get the bufferSize.
     *
     * @return the bufferSize
     */
    public int getBufferSize()
    {
        return _buffers.getBufferSize();
    }

    /**
     * Set the bufferSize.
     *
     * @param bufferSize the bufferSize to set
     */
    public void setBufferSize(int bufferSize)
    {
        if (bufferSize != getBufferSize())
            _buffers = new WebSocketBuffers(bufferSize);
    }

    /**
     * @return The initial maximum text message size (in characters) for a connection
     */
    public int getMaxTextMessageSize()
    {
        return _maxTextMessageSize;
    }

    /**
     * Set the initial maximum text message size for a connection. This can be changed by
     * the application calling {@link WebSocket.Connection#setMaxTextMessageSize(int)}.
     * @param maxTextMessageSize The default maximum text message size (in characters) for a connection
     */
    public void setMaxTextMessageSize(int maxTextMessageSize)
    {
        _maxTextMessageSize = maxTextMessageSize;
    }

    /**
     * @return The initial maximum binary message size (in bytes)  for a connection
     */
    public int getMaxBinaryMessageSize()
    {
        return _maxBinaryMessageSize;
    }

    /**
     * Set the initial maximum binary message size for a connection. This can be changed by
     * the application calling {@link WebSocket.Connection#setMaxBinaryMessageSize(int)}.
     * @param maxBinaryMessageSize The default maximum binary message size (in bytes) for a connection
     */
    public void setMaxBinaryMessageSize(int maxBinaryMessageSize)
    {
        _maxBinaryMessageSize = maxBinaryMessageSize;
    }

    @Override
    protected void doStop() throws Exception
    {
        closeConnections();
    }

    /**
     * Upgrade the request/response to a WebSocket Connection.
     * <p>This method will not normally return, but will instead throw a
     * UpgradeConnectionException, to exit HTTP handling and initiate
     * WebSocket handling of the connection.
     *
     * @param request   The request to upgrade
     * @param response  The response to upgrade
     * @param websocket The websocket handler implementation to use
     * @param protocol  The websocket protocol
     * @throws IOException in case of I/O errors
     */
    public void upgrade(HttpServletRequest request, HttpServletResponse response, WebSocket websocket, String protocol)
            throws IOException
    {
        if (!"websocket".equalsIgnoreCase(request.getHeader("Upgrade")))
            throw new IllegalStateException("!Upgrade:websocket");
        if (!"HTTP/1.1".equals(request.getProtocol()))
            throw new IllegalStateException("!HTTP/1.1");

        int draft = request.getIntHeader("Sec-WebSocket-Version");
        if (draft < 0) {
            // Old pre-RFC version specifications (header not present in RFC-6455)
            draft = request.getIntHeader("Sec-WebSocket-Draft");
        }
        // Remember requested version for possible error message later
        int requestedVersion = draft;
        AbstractHttpConnection http = AbstractHttpConnection.getCurrentConnection();
        if (http instanceof BlockingHttpConnection)
            throw new IllegalStateException("Websockets not supported on blocking connectors");
        ConnectedEndPoint endp = (ConnectedEndPoint)http.getEndPoint();

        List<String> extensions_requested = new ArrayList<String>();
        @SuppressWarnings("unchecked")
        Enumeration<String> e = request.getHeaders("Sec-WebSocket-Extensions");
        while (e.hasMoreElements())
        {
            QuotedStringTokenizer tok = new QuotedStringTokenizer(e.nextElement(),",");
            while (tok.hasMoreTokens())
            {
                extensions_requested.add(tok.nextToken());
            }
        }

        final WebSocketServletConnection connection;
        if (draft<_minVersion)
            draft=Integer.MAX_VALUE;
        switch (draft)
        {
            case -1: // unspecified draft/version (such as early OSX Safari 5.1 and iOS 5.x)
            case 0: // Old school draft/version
            {
                connection = new WebSocketServletConnectionD00(this, websocket, endp, _buffers, http.getTimeStamp(), _maxIdleTime, protocol);
                break;
            }
            case 1:
            case 2:
            case 3:
            case 4:
            case 5:
            case 6:
            {
                connection = new WebSocketServletConnectionD06(this, websocket, endp, _buffers, http.getTimeStamp(), _maxIdleTime, protocol);
                break;
            }
            case 7:
            case 8:
            {
                List<Extension> extensions = initExtensions(extensions_requested, 8 - WebSocketConnectionD08.OP_EXT_DATA, 16 - WebSocketConnectionD08.OP_EXT_CTRL, 3);
                connection = new WebSocketServletConnectionD08(this, websocket, endp, _buffers, http.getTimeStamp(), _maxIdleTime, protocol, extensions, draft);
                break;
            }
            case WebSocketConnectionRFC6455.VERSION: // RFC 6455 Version
            {
                List<Extension> extensions = initExtensions(extensions_requested, 8 - WebSocketConnectionRFC6455.OP_EXT_DATA, 16 - WebSocketConnectionRFC6455.OP_EXT_CTRL, 3);
                connection = new WebSocketServletConnectionRFC6455(this, websocket, endp, _buffers, http.getTimeStamp(), _maxIdleTime, protocol, extensions, draft);
                break;
            }
            default:
            {
                // Per RFC 6455 - 4.4 - Supporting Multiple Versions of WebSocket Protocol
                // Using the examples as outlined
                String versions="13";
                if (_minVersion<=8)
                    versions+=", 8";
                if (_minVersion<=6)
                    versions+=", 6";
                if (_minVersion<=0)
                    versions+=", 0";
                    
                response.setHeader("Sec-WebSocket-Version", versions);

                // Make error clear for developer / end-user
                StringBuilder err = new StringBuilder();
                err.append("Unsupported websocket client version specification ");
                if(requestedVersion >= 0) {
                    err.append("[").append(requestedVersion).append("]");
                } else {
                    err.append("<Unspecified, likely a pre-draft version of websocket>");
                }
                err.append(", configured minVersion [").append(_minVersion).append("]");
                err.append(", reported supported versions [").append(versions).append("]");
                LOG.warn(err.toString()); // Log it
                // use spec language for unsupported versions
                throw new HttpException(400, "Unsupported websocket version specification"); // Tell client
            }
        }

        addConnection(connection);

        // Set the defaults
        connection.getConnection().setMaxBinaryMessageSize(_maxBinaryMessageSize);
        connection.getConnection().setMaxTextMessageSize(_maxTextMessageSize);

        // Let the connection finish processing the handshake
        connection.handshake(request, response, protocol);
        response.flushBuffer();

        // Give the connection any unused data from the HTTP connection.
        connection.fillBuffersFrom(((HttpParser)http.getParser()).getHeaderBuffer());
        connection.fillBuffersFrom(((HttpParser)http.getParser()).getBodyBuffer());

        // Tell jetty about the new connection
        LOG.debug("Websocket upgrade {} {} {} {}",request.getRequestURI(),draft,protocol,connection);
        request.setAttribute("org.eclipse.jetty.io.Connection", connection);
    }

    protected String[] parseProtocols(String protocol)
    {
        if (protocol == null)
            return new String[]{null};
        protocol = protocol.trim();
        if (protocol == null || protocol.length() == 0)
            return new String[]{null};
        String[] passed = protocol.split("\\s*,\\s*");
        String[] protocols = new String[passed.length + 1];
        System.arraycopy(passed, 0, protocols, 0, passed.length);
        return protocols;
    }

    public boolean acceptWebSocket(HttpServletRequest request, HttpServletResponse response)
            throws IOException
    {
        if ("websocket".equalsIgnoreCase(request.getHeader("Upgrade")))
        {
            String origin = request.getHeader("Origin");
            if (origin==null)
                origin = request.getHeader("Sec-WebSocket-Origin");
            if (!_acceptor.checkOrigin(request,origin))
            {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return false;
            }

            // Try each requested protocol
            WebSocket websocket = null;

            @SuppressWarnings("unchecked")
            Enumeration<String> protocols = request.getHeaders("Sec-WebSocket-Protocol");
            String protocol=null;
            while (protocol==null && protocols!=null && protocols.hasMoreElements())
            {
                String candidate = protocols.nextElement();
                for (String p : parseProtocols(candidate))
                {
                    websocket = _acceptor.doWebSocketConnect(request, p);
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
                websocket = _acceptor.doWebSocketConnect(request, null);

                if (websocket==null)
                {
                    response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    return false;
                }
            }

            // Send the upgrade
            upgrade(request, response, websocket, protocol);
            return true;
        }

        return false;
    }

    public List<Extension> initExtensions(List<String> requested,int maxDataOpcodes,int maxControlOpcodes,int maxReservedBits)
    {
        List<Extension> extensions = new ArrayList<Extension>();
        for (String rExt : requested)
        {
            QuotedStringTokenizer tok = new QuotedStringTokenizer(rExt,";");
            String extName=tok.nextToken().trim();
            Map<String,String> parameters = new HashMap<String,String>();
            while (tok.hasMoreTokens())
            {
                QuotedStringTokenizer nv = new QuotedStringTokenizer(tok.nextToken().trim(),"=");
                String name=nv.nextToken().trim();
                String value=nv.hasMoreTokens()?nv.nextToken().trim():null;
                parameters.put(name,value);
            }

            Extension extension = newExtension(extName);

            if (extension==null)
                continue;

            if (extension.init(parameters))
            {
                LOG.debug("add {} {}",extName,parameters);
                extensions.add(extension);
            }
        }
        LOG.debug("extensions={}",extensions);
        return extensions;
    }

    private Extension newExtension(String name)
    {
        try
        {
            Class<? extends Extension> extClass = _extensionClasses.get(name);
            if (extClass!=null)
                return extClass.newInstance();
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }

        return null;
    }

    protected boolean addConnection(WebSocketServletConnection connection)
    {
        return isRunning() && connections.add(connection);
    }

    protected boolean removeConnection(WebSocketServletConnection connection)
    {
        return connections.remove(connection);
    }

    protected void closeConnections()
    {
        for (WebSocketServletConnection connection : connections)
            connection.shutdown();
    }
}
