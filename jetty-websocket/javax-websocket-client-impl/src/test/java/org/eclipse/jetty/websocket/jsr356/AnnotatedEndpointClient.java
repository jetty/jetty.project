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

package org.eclipse.jetty.websocket.jsr356;

import java.nio.ByteBuffer;

import javax.websocket.ClientEndpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.jsr356.decoders.DateDecoder;
import org.eclipse.jetty.websocket.jsr356.encoders.TimeEncoder;

@ClientEndpoint(
        subprotocols = { "chat", "echo" },
        decoders = { DateDecoder.class },
        encoders = { TimeEncoder.class },
        configurator = AnnotatedEndpointConfigurator.class)
public class AnnotatedEndpointClient
{
    public Session session;
    public EndpointConfig config;

    @OnOpen
    public void onOpen(Session session, EndpointConfig config)
    {
        this.session = session;
        this.config = config;
    }

    @OnMessage(maxMessageSize = 111222)
    public void onText(String msg)
    {
        /* do nothing */
    }

    @OnMessage(maxMessageSize = 333444)
    public void onBinary(ByteBuffer buf)
    {
        /* do nothing */
    }
}
