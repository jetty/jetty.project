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

package org.eclipse.jetty.websocket.jsr356.endpoints;

import javax.websocket.EndpointConfig;

import org.eclipse.jetty.websocket.jsr356.metadata.EndpointMetadata;

/**
 * Associate a JSR Endpoint with its optional {@link EndpointConfig}
 */
public class EndpointInstance
{
    /**
     * The instance of the Endpoint
     */
    private final Object endpoint;
    /**
     * The instance specific configuration for the Endpoint
     */
    private final EndpointConfig config;
    /**
     * The metadata for this endpoint
     */
    private final EndpointMetadata metadata;

    public EndpointInstance(Object endpoint, EndpointConfig config, EndpointMetadata metadata)
    {
        this.endpoint = endpoint;
        this.config = config;
        this.metadata = metadata;
    }

    public EndpointConfig getConfig()
    {
        return config;
    }

    public Object getEndpoint()
    {
        return endpoint;
    }

    public EndpointMetadata getMetadata()
    {
        return metadata;
    }
}
