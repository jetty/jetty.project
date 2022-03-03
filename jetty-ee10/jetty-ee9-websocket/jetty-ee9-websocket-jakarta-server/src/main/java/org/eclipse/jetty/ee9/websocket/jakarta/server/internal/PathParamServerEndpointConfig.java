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

package org.eclipse.jetty.ee9.websocket.jakarta.server.internal;

import java.util.HashMap;
import java.util.Map;

import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.ee9.websocket.jakarta.common.PathParamProvider;
import org.eclipse.jetty.ee9.websocket.jakarta.common.ServerEndpointConfigWrapper;
import org.eclipse.jetty.util.URIUtil;

/**
 * Make {@link jakarta.websocket.server.PathParam} information from the incoming request available
 * on {@link ServerEndpointConfig}
 */
public class PathParamServerEndpointConfig extends ServerEndpointConfigWrapper implements PathParamProvider
{
    private final Map<String, String> pathParamMap;

    public PathParamServerEndpointConfig(ServerEndpointConfig config, Map<String, String> pathParams)
    {
        super(config);

        pathParamMap = new HashMap<>();
        if (pathParams != null)
            pathParams.forEach((key, value) -> pathParamMap.put(key, URIUtil.decodePath(value)));
    }

    @Override
    public Map<String, String> getPathParams()
    {
        return pathParamMap;
    }
}
