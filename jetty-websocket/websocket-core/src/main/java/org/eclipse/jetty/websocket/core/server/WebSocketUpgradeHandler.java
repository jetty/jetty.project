//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.server;

import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.FrameHandler;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.Function;

public class WebSocketUpgradeHandler extends HandlerWrapper
{
    final static Logger LOG = Log.getLogger(WebSocketUpgradeHandler.class);
    final Handshaker handshaker = Handshaker.newInstance();
    final PathSpecSet paths = new PathSpecSet();
    final WebSocketNegotiator negotiator;

    public WebSocketUpgradeHandler(
        Function<Negotiation, FrameHandler> negotiate,
        String... pathSpecs)
    {
        this(WebSocketNegotiator.from(negotiate), pathSpecs);
    }

    public WebSocketUpgradeHandler(WebSocketNegotiator negotiator, String... pathSpecs)
    {
        this.negotiator = negotiator;
        addPathSpec(pathSpecs);
    }

    public WebSocketNegotiator getWebSocketNegotiator()
    {
        return negotiator;
    }

    public void addPathSpec(String... pathSpecs)
    {
        if (pathSpecs != null)
        {
            for (String spec : pathSpecs)
                this.paths.add(spec);
        }
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (!paths.isEmpty() && !paths.test(target))
        {
            super.handle(target, baseRequest, request, response);
            return;
        }

        if (handshaker.upgradeRequest(negotiator, request, response, null))
            return;

        if (!baseRequest.isHandled())
            super.handle(target, baseRequest, request, response);
    }
}
