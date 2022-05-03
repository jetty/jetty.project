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

package org.eclipse.jetty.ee10.websocket.jakarta.common;

import jakarta.websocket.EndpointConfig;

/**
 * Associate a JSR Endpoint with its optional {@link EndpointConfig}
 */
public class ConfiguredEndpoint
{
    /**
     * The instance of the Endpoint
     */
    private Object endpoint;
    /**
     * The optional instance specific configuration for the Endpoint
     */
    private final EndpointConfig config;

    public ConfiguredEndpoint(Object endpoint, EndpointConfig config)
    {
        this.endpoint = endpoint;
        this.config = config;
    }

    public EndpointConfig getConfig()
    {
        return config;
    }

    public Object getRawEndpoint()
    {
        return endpoint;
    }

    public void setRawEndpoint(Object rawEndpoint)
    {
        this.endpoint = rawEndpoint;
    }
}
