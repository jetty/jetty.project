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

package org.eclipse.jetty.websocket.javax.server.internal;

import java.util.HashMap;
import java.util.Map;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.websocket.javax.common.PathParamProvider;
import org.eclipse.jetty.websocket.javax.common.ServerEndpointConfigWrapper;

/**
 * Make {@link javax.websocket.server.PathParam} information from the incoming request available
 * on {@link ServerEndpointConfig}
 */
public class PathParamServerEndpointConfig extends ServerEndpointConfigWrapper implements PathParamProvider
{
    private final Map<String, String> pathParamMap;

    public PathParamServerEndpointConfig(ServerEndpointConfig config, UriTemplatePathSpec pathSpec, String requestPath)
    {
        super(config);

        Map<String, String> pathMap = pathSpec.getPathParams(requestPath);
        pathParamMap = new HashMap<>();
        if (pathMap != null)
        {
            pathMap.entrySet().stream().forEach(
                entry -> pathParamMap.put(entry.getKey(), URIUtil.decodePath(entry.getValue()))
            );
        }
    }

    @Override
    public Map<String, String> getPathParams()
    {
        return pathParamMap;
    }
}
