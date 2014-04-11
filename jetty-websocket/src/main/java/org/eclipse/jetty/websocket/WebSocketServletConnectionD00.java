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
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.QuotedStringTokenizer;

public class WebSocketServletConnectionD00 extends WebSocketConnectionD00 implements WebSocketServletConnection
{
    private final WebSocketFactory factory;

    public WebSocketServletConnectionD00(WebSocketFactory factory, WebSocket websocket, EndPoint endpoint, WebSocketBuffers buffers, long timestamp, int maxIdleTime, String protocol)
            throws IOException
    {
        super(websocket,endpoint,buffers,timestamp,maxIdleTime,protocol);
        this.factory = factory;
    }

    public void handshake(HttpServletRequest request, HttpServletResponse response, String subprotocol) throws IOException
    {
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        if (query != null && query.length() > 0)
        {
            uri += "?" + query;
        }
        uri = new HttpURI(uri).toString();
        String host = request.getHeader("Host");

        String origin = request.getHeader("Sec-WebSocket-Origin");
        if (origin == null)
        {
            origin = request.getHeader("Origin");
        }
        if (origin != null)
        {
            origin = QuotedStringTokenizer.quoteIfNeeded(origin,"\r\n");
        }

        String key1 = request.getHeader("Sec-WebSocket-Key1");

        if (key1 != null)
        {
            String key2 = request.getHeader("Sec-WebSocket-Key2");
            setHixieKeys(key1,key2);

            response.setHeader("Upgrade","WebSocket");
            response.addHeader("Connection","Upgrade");
            if (origin != null)
            {
                response.addHeader("Sec-WebSocket-Origin",origin);
            }
            response.addHeader("Sec-WebSocket-Location",(request.isSecure()?"wss://":"ws://") + host + uri);
            if (subprotocol != null)
            {
                response.addHeader("Sec-WebSocket-Protocol",subprotocol);
            }
            response.sendError(101, "WebSocket Protocol Handshake");
        }
        else
        {
            response.setHeader("Upgrade","WebSocket");
            response.addHeader("Connection","Upgrade");
            response.addHeader("WebSocket-Origin",origin);
            response.addHeader("WebSocket-Location",(request.isSecure()?"wss://":"ws://") + host + uri);
            if (subprotocol != null)
            {
                response.addHeader("WebSocket-Protocol",subprotocol);
            }
            response.sendError(101,"Web Socket Protocol Handshake");
            response.flushBuffer();

            onFrameHandshake();
            onWebsocketOpen();
        }
    }

    @Override
    public void onClose()
    {
        super.onClose();
        factory.removeConnection(this);
    }
}
