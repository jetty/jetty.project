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

import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class WebSocketUpgradeHandler extends HandlerWrapper
{
    final static Logger LOG = Log.getLogger(WebSocketUpgradeHandler.class);

    final PathSpecSet paths = new PathSpecSet();
    final WebSocketNegotiator negotiator;

    public WebSocketUpgradeHandler(WebSocketNegotiator negotiator, String... pathSpecs)
    {
        this.negotiator = negotiator;
        addPathSpec(pathSpecs);
    }

    public void addPathSpec(String... pathSpecs)
    {
        if (pathSpecs!=null)
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
            super.handle(target,baseRequest,request,response);
            return;
        }

        Handshaker handshaker = HandshakerFactory.getHandshaker(request);
        if (LOG.isDebugEnabled())
            LOG.debug("handle {} handshaker={}",baseRequest,handshaker);
        if (handshaker!=null && handshaker.upgradeRequest(negotiator, request, response))
            return;
        if (!baseRequest.isHandled())
            super.handle(target,baseRequest,request,response);
    }
}
