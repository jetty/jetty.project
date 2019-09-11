//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.server.internal;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.server.Handshaker;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;

public class HandshakerSelector implements Handshaker
{
    private final RFC6455Handshaker rfc6455 = new RFC6455Handshaker();
    private final RFC8441Handshaker rfc8441 = new RFC8441Handshaker();

    @Override
    public boolean upgradeRequest(WebSocketNegotiator negotiator, HttpServletRequest request, HttpServletResponse response, FrameHandler.Customizer defaultCustomizer) throws IOException
    {
        Request baseRequest = Request.getBaseRequest(request);
        String method = request.getMethod();
        HttpVersion httpVersion = baseRequest.getHttpVersion();

        if (HttpMethod.GET.equals(method) && HttpVersion.HTTP_1_1.equals(httpVersion))
        {
            return rfc6455.upgradeRequest(negotiator, request, response, defaultCustomizer);
        }
        else if (HttpMethod.CONNECT.equals(method) && HttpVersion.HTTP_2.equals(httpVersion))
        {
            return rfc8441.upgradeRequest(negotiator, request, response, defaultCustomizer);
        }

        return false;
    }
}
