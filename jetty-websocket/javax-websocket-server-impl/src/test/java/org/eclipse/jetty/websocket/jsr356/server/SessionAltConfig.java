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

import java.util.HashSet;
import java.util.Set;

import javax.websocket.Endpoint;
import javax.websocket.server.ServerApplicationConfig;
import javax.websocket.server.ServerEndpointConfig;

public class SessionAltConfig implements ServerApplicationConfig
{
    @Override
    public Set<ServerEndpointConfig> getEndpointConfigs(Set<Class<? extends Endpoint>> endpointClasses)
    {
        Set<ServerEndpointConfig> configs = new HashSet<>();
        Class<?> endpointClass = SessionInfoSocket.class;
        configs.add(ServerEndpointConfig.Builder.create(endpointClass,"/info/{a}/").build());
        configs.add(ServerEndpointConfig.Builder.create(endpointClass,"/info/{a}/{b}/").build());
        configs.add(ServerEndpointConfig.Builder.create(endpointClass,"/info/{a}/{b}/{c}/").build());
        configs.add(ServerEndpointConfig.Builder.create(endpointClass,"/info/{a}/{b}/{c}/{d}/").build());
        endpointClass = SessionInfoEndpoint.class;
        configs.add(ServerEndpointConfig.Builder.create(endpointClass,"/einfo/").build());
        configs.add(ServerEndpointConfig.Builder.create(endpointClass,"/einfo/{a}/").build());
        configs.add(ServerEndpointConfig.Builder.create(endpointClass,"/einfo/{a}/{b}/").build());
        configs.add(ServerEndpointConfig.Builder.create(endpointClass,"/einfo/{a}/{b}/{c}/").build());
        configs.add(ServerEndpointConfig.Builder.create(endpointClass,"/einfo/{a}/{b}/{c}/{d}/").build());
        return configs;
    }

    @Override
    public Set<Class<?>> getAnnotatedEndpointClasses(Set<Class<?>> scanned)
    {
        Set<Class<?>> annotated = new HashSet<>();
        annotated.add(SessionInfoSocket.class);
        return annotated;
    }
}
