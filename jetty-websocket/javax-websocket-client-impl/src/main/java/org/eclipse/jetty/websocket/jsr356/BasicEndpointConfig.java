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

package org.eclipse.jetty.websocket.jsr356;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;

/**
 * Basic EndpointConfig (used when no EndpointConfig is provided or discovered)
 */
public class BasicEndpointConfig implements EndpointConfig
{
    private List<Class<? extends Decoder>> decoders;
    private List<Class<? extends Encoder>> encoders;
    private Map<String, Object> userProperties;

    public BasicEndpointConfig()
    {
        decoders = Collections.emptyList();
        encoders = Collections.emptyList();
        userProperties = new HashMap<>();
    }

    @Override
    public List<Class<? extends Decoder>> getDecoders()
    {
        return decoders;
    }

    @Override
    public List<Class<? extends Encoder>> getEncoders()
    {
        return encoders;
    }

    @Override
    public Map<String, Object> getUserProperties()
    {
        return userProperties;
    }
}
