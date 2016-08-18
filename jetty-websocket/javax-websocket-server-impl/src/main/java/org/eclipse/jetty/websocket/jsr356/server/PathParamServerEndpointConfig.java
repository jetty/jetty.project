//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.server;

import java.util.HashMap;
import java.util.Map;

import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.websocket.jsr356.PathParamProvider;

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
            pathParamMap.putAll(pathMap);
        }
    }
    
    @Override
    public Map<String, String> getPathParams()
    {
        return pathParamMap;
    }
}
