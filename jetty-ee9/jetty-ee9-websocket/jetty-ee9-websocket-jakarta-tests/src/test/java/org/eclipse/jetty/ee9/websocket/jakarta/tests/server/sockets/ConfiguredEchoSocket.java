//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.jakarta.tests.server.sockets;

import java.nio.ByteBuffer;
import java.util.Locale;

import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.coders.DateDecoder;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.coders.TimeEncoder;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.server.configs.EchoSocketConfigurator;

import static java.util.Comparator.naturalOrder;
import static java.util.stream.Collectors.joining;

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
    private Session session;
    private EndpointConfig config;
    private ServerEndpointConfig serverConfig;

    @SuppressWarnings("unused")
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

    @SuppressWarnings("unused")
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
                return config.getDecoders().stream().map(Class::getName).collect(joining(", "));
            case "encoders":
                return config.getEncoders().stream().map(Class::getName).collect(joining(", "));
            case "subprotocols":
                if (serverConfig == null)
                {
                    return "<not a ServerEndpointConfig>";
                }
                else
                {
                    return serverConfig.getSubprotocols().stream().sorted(naturalOrder()).collect(joining(", "));
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

    @SuppressWarnings("unused")
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
