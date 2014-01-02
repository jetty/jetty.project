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
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.EndPoint;

public class WebSocketServletConnectionRFC6455 extends WebSocketConnectionRFC6455 implements WebSocketServletConnection
{
    private final WebSocketFactory factory;

    public WebSocketServletConnectionRFC6455(WebSocketFactory factory, WebSocket websocket, EndPoint endpoint, WebSocketBuffers buffers, long timestamp, int maxIdleTime, String protocol,
            List<Extension> extensions, int draft) throws IOException
    {
        super(websocket,endpoint,buffers,timestamp,maxIdleTime,protocol,extensions,draft);
        this.factory = factory;
    }

    /* ------------------------------------------------------------ */
    public void handshake(HttpServletRequest request, HttpServletResponse response, String subprotocol) throws IOException
    {
        String key = request.getHeader("Sec-WebSocket-Key");

        response.setHeader("Upgrade","WebSocket");
        response.addHeader("Connection","Upgrade");
        response.addHeader("Sec-WebSocket-Accept",hashKey(key));
        if (subprotocol != null)
        {
            response.addHeader("Sec-WebSocket-Protocol",subprotocol);
        }

        for (Extension ext : getExtensions())
        {
            response.addHeader("Sec-WebSocket-Extensions",ext.getParameterizedName());
        }

        response.sendError(101);

        onFrameHandshake();
        onWebSocketOpen();
    }

    @Override
    public void onClose()
    {
        super.onClose();
        factory.removeConnection(this);
    }
}
