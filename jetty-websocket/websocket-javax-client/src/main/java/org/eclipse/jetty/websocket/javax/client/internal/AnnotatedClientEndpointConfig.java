//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.client.internal;

import java.util.Collections;
import java.util.List;
import javax.websocket.ClientEndpoint;
import javax.websocket.ClientEndpointConfig;

import org.eclipse.jetty.websocket.core.exception.InvalidWebSocketException;
import org.eclipse.jetty.websocket.javax.common.ClientEndpointConfigWrapper;

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
