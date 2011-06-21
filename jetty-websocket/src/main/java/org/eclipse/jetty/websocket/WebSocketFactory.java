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

/**
 * Factory to create WebSocket connections
 */
public class WebSocketFactory
{
    public interface Acceptor
    {
        WebSocket doWebSocketConnect(HttpServletRequest request, String protocol);

        String checkOrigin(HttpServletRequest request, String host, String origin);
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
     * Upgrade the request/response to a WebSocket Connection.
     * <p>This method will not normally return, but will instead throw a
     * UpgradeConnectionException, to exit HTTP handling and initiate
     * WebSocket handling of the connection.
     *
     * @param request   The request to upgrade
     * @param response  The response to upgrade
     * @param websocket The websocket handler implementation to use
     * @param origin    The origin of the websocket connection
     * @param protocol  The websocket protocol
     * @throws IOException in case of I/O errors
     */
    public void upgrade(HttpServletRequest request, HttpServletResponse response, WebSocket websocket, String origin, String protocol)
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
                extensions= initExtensions(extensions_requested,8-WebSocketConnectionD7_9.OP_EXT_DATA, 16-WebSocketConnectionD7_9.OP_EXT_CTRL,3);
                connection = new WebSocketConnectionD7_9(websocket, endp, _buffers, http.getTimeStamp(), _maxIdleTime, protocol,extensions,draft);
                break;
            default:
                Log.warn("Unsupported Websocket version: "+draft);
                throw new HttpException(400, "Unsupported draft specification: " + draft);
        }

        // Let the connection finish processing the handshake
        connection.handshake(request, response, origin, protocol);
        response.flushBuffer();

        // Give the connection any unused data from the HTTP connection.
        connection.fillBuffersFrom(((HttpParser)http.getParser()).getHeaderBuffer());
        connection.fillBuffersFrom(((HttpParser)http.getParser()).getBodyBuffer());

        // Tell jetty about the new connection
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
            String protocol = request.getHeader("Sec-WebSocket-Protocol");
            if (protocol == null) // TODO remove once draft period is over
                protocol = request.getHeader("WebSocket-Protocol");

            WebSocket websocket = null;
            for (String p : parseProtocols(protocol))
            {
                websocket = _acceptor.doWebSocketConnect(request, p);
                if (websocket != null)
                {
                    protocol = p;
                    break;
                }
            }

            String host = request.getHeader("Host");
            String origin = request.getHeader("Origin");
            origin = _acceptor.checkOrigin(request, host, origin);

            if (websocket != null)
            {
                upgrade(request, response, websocket, origin, protocol);
                return true;
            }

            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
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
                Log.debug("add {} {}",extName,parameters);
                extensions.add(extension);
            }
        }
        Log.debug("extensions={}",extensions);
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
            Log.warn(e);
        }
        
        return null;
    }
    
    
}
