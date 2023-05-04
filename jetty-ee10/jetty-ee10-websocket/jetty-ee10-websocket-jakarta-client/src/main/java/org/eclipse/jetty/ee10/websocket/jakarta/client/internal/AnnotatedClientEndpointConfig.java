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

package org.eclipse.jetty.ee10.websocket.jakarta.client.internal;

import java.util.Collections;
import java.util.List;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.DeploymentException;
import org.eclipse.jetty.ee10.websocket.jakarta.common.ClientEndpointConfigWrapper;
import org.eclipse.jetty.websocket.core.WebSocketComponents;

public class AnnotatedClientEndpointConfig extends ClientEndpointConfigWrapper
{
    public AnnotatedClientEndpointConfig(ClientEndpoint anno, WebSocketComponents components) throws DeploymentException
    {
        try
        {
            Configurator configurator = components.getObjectFactory().createInstance(anno.configurator());
            ClientEndpointConfig build = Builder.create()
                .encoders(List.of(anno.encoders()))
                .decoders(List.of(anno.decoders()))
                .preferredSubprotocols(List.of(anno.subprotocols()))
                .extensions(Collections.emptyList())
                .configurator(configurator)
                .build();
            init(build);
        }
        catch (Throwable t)
        {
            String err = "Unable to instantiate ClientEndpoint.configurator() of " + anno.configurator().getName() +
                " defined as annotation in " + anno.getClass().getName();
            throw new DeploymentException(err, t);
        }
    }
}
