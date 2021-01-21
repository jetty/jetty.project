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

package org.eclipse.jetty.websocket.jsr356.server.samples.echo;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.websocket.EndpointConfig;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.jsr356.server.samples.beans.DateDecoder;
import org.eclipse.jetty.websocket.jsr356.server.samples.beans.TimeEncoder;

/**
 * Annotated echo socket, using all of the annotation configurations
 */
@ServerEndpoint(
    value = "/echo",
    decoders = {DateDecoder.class},
    encoders = {TimeEncoder.class},
    subprotocols = {"test", "echo", "chat"},
    configurator = EchoSocketConfigurator.class)
public class ConfiguredEchoSocket
{
    private static final Logger LOG = Log.getLogger(ConfiguredEchoSocket.class);
    private Session session;
    private EndpointConfig config;
    private ServerEndpointConfig serverConfig;

    @OnOpen
    public void onOpen(Session session, EndpointConfig config)
    {
        this.session = session;
        this.config = config;
        if (config instanceof ServerEndpointConfig)
        {
            this.serverConfig = (ServerEndpointConfig)config;
        }
    }

    @OnError
    public void onError(Throwable cause)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug(cause);
        }
    }

    @OnMessage(maxMessageSize = 111222)
    public String echoText(String msg)
    {
        switch (msg)
        {
            case "text-max":
                return String.format(Locale.US, "%,d", session.getMaxTextMessageBufferSize());
            case "binary-max":
                return String.format(Locale.US, "%,d", session.getMaxBinaryMessageBufferSize());
            case "decoders":
                return join(config.getDecoders(), ", ");
            case "encoders":
                return join(config.getEncoders(), ", ");
            case "subprotocols":
                if (serverConfig == null)
                {
                    return "<not a ServerEndpointConfig>";
                }
                else
                {
                    List<String> protocols = new ArrayList<>();
                    protocols.addAll(serverConfig.getSubprotocols());
                    Collections.sort(protocols);
                    return join(protocols, ", ");
                }
            case "configurator":
                if (serverConfig == null)
                {
                    return "<not a ServerEndpointConfig>";
                }
                else
                {
                    return serverConfig.getConfigurator().getClass().getName();
                }
            default:
                // normal echo
                return msg;
        }
    }

    private String join(Collection<?> coll, String delim)
    {
        StringBuilder buf = new StringBuilder();
        boolean needDelim = false;
        for (Object obj : coll)
        {
            if (needDelim)
            {
                buf.append(delim);
            }
            buf.append(Objects.toString(obj));
            needDelim = true;
        }

        return buf.toString();
    }

    @OnMessage(maxMessageSize = 333444)
    public ByteBuffer echoBinary(ByteBuffer buf)
    {
        // this one isn't that important, just here to satisfy the @OnMessage
        // settings that we actually test for via "binary-max" TEXT message
        ByteBuffer ret = buf.slice();
        ret.flip();
        return ret;
    }
}
