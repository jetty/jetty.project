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

package org.eclipse.jetty.websocket.jsr356.server;

import java.util.List;
import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.util.QuoteUtil;

public class BasicServerEndpointConfigurator extends ServerEndpointConfig.Configurator
{
    private static final Logger LOG = Log.getLogger(BasicServerEndpointConfigurator.class);
    private static final String NO_SUBPROTOCOL = "";
    public static final ServerEndpointConfig.Configurator INSTANCE = new BasicServerEndpointConfigurator();

    @Override
    public boolean checkOrigin(String originHeaderValue)
    {
        return true;
    }

    @Override
    public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug(".getEndpointInstance({})",endpointClass);
        }
        try
        {
            return endpointClass.newInstance();
        }
        catch (IllegalAccessException e)
        {
            throw new InstantiationException(String.format("%s: %s",e.getClass().getName(),e.getMessage()));
        }
    }

    @Override
    public List<Extension> getNegotiatedExtensions(List<Extension> installed, List<Extension> requested)
    {
        return requested;
    }

    @Override
    public String getNegotiatedSubprotocol(List<String> supported, List<String> requested)
    {
        if ((requested == null) || (requested.size() == 0))
        {
            // nothing requested, don't return anything
            return NO_SUBPROTOCOL;
        }

        // Nothing specifically called out as being supported by the endpoint
        if ((supported == null) || (supported.isEmpty()))
        {
            // Just return the first hit in this case
            LOG.warn("Client requested Subprotocols on endpoint with none supported: {}",QuoteUtil.join(requested,","));
            return NO_SUBPROTOCOL;
        }

        // Return the first matching hit from the list of supported protocols.
        for (String possible : requested)
        {
            if (possible == null)
            {
                // skip null
                continue;
            }

            if (supported.contains(possible))
            {
                return possible;
            }
        }

        LOG.warn("Client requested subprotocols {} do not match any endpoint supported subprotocols {}",QuoteUtil.join(requested,","),
                QuoteUtil.join(supported,","));
        return NO_SUBPROTOCOL;
    }

    @Override
    public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
    {
        /* do nothing */
    }
}
