//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.javax.common;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;

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
