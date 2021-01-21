//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.ServiceLoader;
import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpointConfig;
import javax.websocket.server.ServerEndpointConfig.Configurator;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.util.QuoteUtil;

/**
 * The "Container Default Configurator" per the JSR-356 spec.
 *
 * @see ServiceLoader behavior of {@link javax.websocket.server.ServerEndpointConfig.Configurator}
 */
public final class ContainerDefaultConfigurator extends Configurator
{
    private static final Logger LOG = Log.getLogger(ContainerDefaultConfigurator.class);
    private static final String NO_SUBPROTOCOL = "";

    /**
     * Default Constructor required, as
     * javax.websocket.server.ServerEndpointConfig$Configurator.fetchContainerDefaultConfigurator()
     * will be the one that instantiates this class in most cases.
     */
    public ContainerDefaultConfigurator()
    {
        super();
    }

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
            LOG.debug(".getEndpointInstance({})", endpointClass);
        }

        try
        {
            // Since this is started via a ServiceLoader, this class has no Scope or context
            // that can be used to obtain a ObjectFactory from.
            return endpointClass.getConstructor().newInstance();
        }
        catch (Exception e)
        {
            String errorMsg = String.format("%s: %s", e.getClass().getName(), e.getMessage());
            InstantiationException instantiationException = new InstantiationException(errorMsg);
            instantiationException.initCause(e);
            throw instantiationException;
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
            LOG.warn("Client requested Subprotocols on endpoint with none supported: {}", QuoteUtil.join(requested, ","));
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

        LOG.warn("Client requested subprotocols {} do not match any endpoint supported subprotocols {}", QuoteUtil.join(requested, ","),
            QuoteUtil.join(supported, ","));
        return NO_SUBPROTOCOL;
    }

    @Override
    public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
    {
        /* do nothing */
    }
}
