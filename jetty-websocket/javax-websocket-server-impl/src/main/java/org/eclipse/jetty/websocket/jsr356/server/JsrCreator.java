//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.server;

import java.io.IOException;
import java.util.List;

import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.jsr356.endpoints.EndpointInstance;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;

public class JsrCreator implements WebSocketCreator
{
    private static final Logger LOG = Log.getLogger(JsrCreator.class);
    private final ServerEndpointMetadata metadata;

    public JsrCreator(ServerEndpointMetadata metadata)
    {
        this.metadata = metadata;
    }

    @Override
    public Object createWebSocket(UpgradeRequest req, UpgradeResponse resp)
    {
        JsrHandshakeRequest hsreq = new JsrHandshakeRequest(req);
        JsrHandshakeResponse hsresp = new JsrHandshakeResponse(resp);
        
        ServerEndpointConfig config = metadata.getConfig();

        ServerEndpointConfig.Configurator configurator = config.getConfigurator();

        // modify handshake
        configurator.modifyHandshake(config,hsreq,hsresp);

        // check origin
        if (!configurator.checkOrigin(req.getOrigin()))
        {
            try
            {
                resp.sendForbidden("Origin mismatch");
            }
            catch (IOException e)
            {
                LOG.debug("Unable to send error response",e);
            }
            return null;
        }

        // deal with sub protocols
        List<String> supported = config.getSubprotocols();
        List<String> requested = req.getSubProtocols();
        String subprotocol = configurator.getNegotiatedSubprotocol(supported,requested);
        if (subprotocol != null)
        {
            resp.setAcceptedSubProtocol(subprotocol);
        }

        // create endpoint class
        try
        {
            Class<?> endpointClass = config.getEndpointClass();
            Object endpoint = config.getConfigurator().getEndpointInstance(endpointClass);
            return new EndpointInstance(endpoint,config,metadata);
        }
        catch (InstantiationException e)
        {
            LOG.debug("Unable to create websocket: " + config.getEndpointClass().getName(),e);
            return null;
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s[metadata=%s]",this.getClass().getName(),metadata);
    }
}
