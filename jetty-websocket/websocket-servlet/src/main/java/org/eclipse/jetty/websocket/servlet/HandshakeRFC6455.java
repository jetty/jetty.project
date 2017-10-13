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

package org.eclipse.jetty.websocket.servlet;

import java.io.IOException;

import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.handshake.AcceptHash;

/**
 * WebSocket Handshake for <a href="https://tools.ietf.org/html/rfc6455">RFC 6455</a>.
 */
public class HandshakeRFC6455 implements WebSocketHandshake
{
    @Override
    public void doHandshakeResponse(ServletUpgradeRequest request, ServletUpgradeResponse response) throws IOException
    {
        String key = request.getHeader(WebSocketConstants.SEC_WEBSOCKET_KEY);

        if (key == null)
        {
            throw new IllegalStateException("Missing request header 'Sec-WebSocket-Key'");
        }

        // build response
        response.setHeader(HttpHeader.UPGRADE.asString(),"WebSocket");
        response.addHeader(HttpHeader.CONNECTION.asString(),"Upgrade");
        response.addHeader(WebSocketConstants.SEC_WEBSOCKET_ACCEPT, AcceptHash.hashKey(key));
        request.complete();

        response.setStatusCode(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
        response.complete();
    }
}
