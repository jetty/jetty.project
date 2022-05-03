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

package org.eclipse.jetty.ee9.websocket.jakarta.common;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import jakarta.websocket.Decoder;
import jakarta.websocket.Encoder;
import jakarta.websocket.EndpointConfig;

public abstract class EndpointConfigWrapper implements EndpointConfig, PathParamProvider
{
    private EndpointConfig _endpointConfig;
    private Map<String, String> _pathParameters;

    public EndpointConfigWrapper()
    {
    }

    public EndpointConfigWrapper(EndpointConfig endpointConfig)
    {
        init(endpointConfig);
    }

    public void init(EndpointConfig endpointConfig)
    {
        _endpointConfig = endpointConfig;

        if (endpointConfig instanceof PathParamProvider)
            _pathParameters = ((PathParamProvider)endpointConfig).getPathParams();
        else
            _pathParameters = Collections.emptyMap();
    }

    @Override
    public List<Class<? extends Encoder>> getEncoders()
    {
        return _endpointConfig.getEncoders();
    }

    @Override
    public List<Class<? extends Decoder>> getDecoders()
    {
        return _endpointConfig.getDecoders();
    }

    @Override
    public Map<String, Object> getUserProperties()
    {
        return _endpointConfig.getUserProperties();
    }

    @Override
    public Map<String, String> getPathParams()
    {
        return _pathParameters;
    }
}
