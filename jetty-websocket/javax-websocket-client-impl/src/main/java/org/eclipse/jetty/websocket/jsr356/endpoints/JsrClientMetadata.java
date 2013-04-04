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

package org.eclipse.jetty.websocket.jsr356.endpoints;

import java.util.LinkedList;

import javax.websocket.ClientEndpoint;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.Decoder;
import javax.websocket.DeploymentException;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.jsr356.DecoderWrapper;
import org.eclipse.jetty.websocket.jsr356.Decoders;
import org.eclipse.jetty.websocket.jsr356.DefaultClientEndpointConfig;
import org.eclipse.jetty.websocket.jsr356.JettyWebSocketContainer;
import org.eclipse.jetty.websocket.jsr356.annotations.IJsrParamId;
import org.eclipse.jetty.websocket.jsr356.annotations.JsrMetadata;
import org.eclipse.jetty.websocket.jsr356.annotations.JsrParamIdBinaryDecoder;
import org.eclipse.jetty.websocket.jsr356.annotations.JsrParamIdTextDecoder;

public class JsrClientMetadata extends JsrMetadata<ClientEndpoint>
{
    private final ClientEndpoint endpoint;
    private final ClientEndpointConfig config;
    private final Decoders decoders;

    public JsrClientMetadata(JettyWebSocketContainer container, Class<?> websocket) throws DeploymentException
    {
        super(websocket);

        ClientEndpoint anno = websocket.getAnnotation(ClientEndpoint.class);
        if (anno == null)
        {
            throw new InvalidWebSocketException("Unsupported WebSocket object, missing @" + ClientEndpoint.class + " annotation");
        }

        this.endpoint = anno;
        this.config = new DefaultClientEndpointConfig(anno.decoders(),anno.encoders());
        this.decoders = new Decoders(container.getDecoderMetadataFactory(),config);
    }

    @Override
    public void customizeParamsOnMessage(LinkedList<IJsrParamId> params)
    {
        for (DecoderWrapper wrapper : decoders.wrapperSet())
        {
            Class<? extends Decoder> decoder = wrapper.getMetadata().getDecoder();

            if (Decoder.Text.class.isAssignableFrom(decoder) || Decoder.TextStream.class.isAssignableFrom(decoder))
            {
                params.add(new JsrParamIdTextDecoder(wrapper));
                continue;
            }

            if (Decoder.Binary.class.isAssignableFrom(decoder) || Decoder.BinaryStream.class.isAssignableFrom(decoder))
            {
                params.add(new JsrParamIdBinaryDecoder(wrapper));
                continue;
            }

            throw new IllegalStateException("Invalid Decoder: " + decoder);
        }
    }

    @Override
    public ClientEndpoint getAnnotation()
    {
        return endpoint;
    }
}
