//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.javax.client;

import java.util.Collections;
import java.util.List;
import javax.websocket.ClientEndpoint;
import javax.websocket.ClientEndpointConfig;

import org.eclipse.jetty.websocket.javax.common.ClientEndpointConfigWrapper;
import org.eclipse.jetty.websocket.javax.common.InvalidWebSocketException;

public class AnnotatedClientEndpointConfig extends ClientEndpointConfigWrapper
{
    public AnnotatedClientEndpointConfig(ClientEndpoint anno)
    {
        Configurator configurator;
        try
        {
            configurator = anno.configurator().getDeclaredConstructor().newInstance();
        }
        catch (Exception e)
        {
            StringBuilder err = new StringBuilder();
            err.append("Unable to instantiate ClientEndpoint.configurator() of ");
            err.append(anno.configurator().getName());
            err.append(" defined as annotation in ");
            err.append(anno.getClass().getName());
            throw new InvalidWebSocketException(err.toString(), e);
        }

        ClientEndpointConfig build = Builder.create()
            .encoders(List.of(anno.encoders()))
            .decoders(List.of(anno.decoders()))
            .preferredSubprotocols(List.of(anno.subprotocols()))
            .extensions(Collections.emptyList())
            .configurator(configurator)
            .build();

        init(build);
    }
}
