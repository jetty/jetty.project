//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.websocket.jakarta.client.internal;

import java.util.Collections;
import java.util.List;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ClientEndpointConfig;
import org.eclipse.jetty.ee10.websocket.jakarta.common.ClientEndpointConfigWrapper;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.exception.InvalidWebSocketException;

public class AnnotatedClientEndpointConfig extends ClientEndpointConfigWrapper
{
    public AnnotatedClientEndpointConfig(ClientEndpoint anno, WebSocketComponents components)
    {
        Configurator configurator;
        try
        {
            configurator = components.getObjectFactory().createInstance(anno.configurator());
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
