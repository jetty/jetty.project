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

package org.eclipse.jetty.websocket;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Factory to create WebSocket connections
 */
public class WebSocketFactory
{
    private static final Logger LOG = Log.getLogger(WebSocketFactory.class);

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
    private int _maxTextMessageSize = 16*1024;
    private int _maxBinaryMessageSize = -1;

    public WebSocketFactory(Acceptor acceptor)
    {
        this(acceptor, 64 * 1024);
    }

    public WebSocketFactory(Acceptor acceptor, int bufferSize)
    {
        _buffers = new WebSocketBuffers(bufferSize);
        _acceptor = acceptor;
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
        if (draft < 0)
            draft = request.getIntHeader("Sec-WebSocket-Draft");
        HttpConnection http = HttpConnection.getCurrentConnection();
        ConnectedEndPoint endp = (ConnectedEndPoint)http.getEndPoint();

        List<String> extensions_requested = new ArrayList<String>();
        for (Enumeration e=request.getHeaders("Sec-WebSocket-Extensions");e.hasMoreElements();)
        {
            QuotedStringTokenizer tok = new QuotedStringTokenizer((String)e.nextElement(),",");
            while (tok.hasMoreTokens())
                extensions_requested.add(tok.nextToken());
        }

        final WebSocketConnection connection;
        final List<Extension> extensions;
        switch (draft)
        {
            case -1:
            case 0:
                extensions=Collections.emptyList();
                connection = new WebSocketConnectionD00(websocket, endp, _buffers, http.getTimeStamp(), _maxIdleTime, protocol);
                break;
            case 6:
                extensions=Collections.emptyList();
                connection = new WebSocketConnectionD06(websocket, endp, _buffers, http.getTimeStamp(), _maxIdleTime, protocol);
                break;
            case 7:
            case 8:
            case 9:
            case 10:
            case 11:
            case 12:
                extensions= initExtensions(extensions_requested,8-WebSocketConnectionD12.OP_EXT_DATA, 16-WebSocketConnectionD13.OP_EXT_CTRL,3);
                connection = new WebSocketConnectionD12(websocket, endp, _buffers, http.getTimeStamp(), _maxIdleTime, protocol,extensions,draft);
                break;
            case 13:
            case 14:
                extensions= initExtensions(extensions_requested,8-WebSocketConnectionD13.OP_EXT_DATA, 16-WebSocketConnectionD13.OP_EXT_CTRL,3);
                connection = new WebSocketConnectionD13(websocket, endp, _buffers, http.getTimeStamp(), _maxIdleTime, protocol,extensions,draft);
                break;
            default:
                LOG.warn("Unsupported Websocket version: "+draft);
                response.setHeader("Sec-WebSocket-Version","0,6,12,13,14");
                throw new HttpException(400, "Unsupported draft specification: " + draft);
        }

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
        request.setAttribute("org.eclipse.jetty.io.Connection", connection);
    }

    /**
     */
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

    /**
     */
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

    /**
     */
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

    /**
     */
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
}
