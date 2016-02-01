//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.server.browser;

import java.util.Collections;
import java.util.List;

import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.websocket.api.util.QuoteUtil;

public class JsrBrowserConfigurator extends ServerEndpointConfig.Configurator
{
    @Override
    public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
    {
        super.modifyHandshake(sec,request,response);
        sec.getUserProperties().put("userAgent",getHeaderValue(request,"User-Agent"));
        sec.getUserProperties().put("requestedExtensions",getHeaderValue(request,"Sec-WebSocket-Extensions"));
    }

    private String getHeaderValue(HandshakeRequest request, String key)
    {
        List<String> value = request.getHeaders().get(key);
        return QuoteUtil.join(value,",");
    }

    @Override
    public List<Extension> getNegotiatedExtensions(List<Extension> installed, List<Extension> requested)
    {
        return Collections.emptyList();
    }
}
