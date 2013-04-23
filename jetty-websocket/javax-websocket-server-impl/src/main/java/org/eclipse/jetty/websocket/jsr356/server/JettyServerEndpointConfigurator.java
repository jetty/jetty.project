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

import java.util.List;

import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpointConfig;
import javax.websocket.server.ServerEndpointConfig.Configurator;

public class JettyServerEndpointConfigurator extends Configurator
{
    @Override
    public boolean checkOrigin(String originHeaderValue)
    {
        return super.checkOrigin(originHeaderValue);
    }

    @Override
    public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException
    {
        return super.getEndpointInstance(endpointClass);
    }

    @Override
    public List<Extension> getNegotiatedExtensions(List<Extension> installed, List<Extension> requested)
    {
        return super.getNegotiatedExtensions(installed,requested);
    }

    @Override
    public String getNegotiatedSubprotocol(List<String> supported, List<String> requested)
    {
        return super.getNegotiatedSubprotocol(supported,requested);
    }

    @Override
    public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
    {
        super.modifyHandshake(sec,request,response);
    }
}
