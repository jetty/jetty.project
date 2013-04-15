//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.util.concurrent.ConcurrentHashMap;

import javax.websocket.DeploymentException;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.annotations.AnnotatedEndpointScanner;

public class ServerContainer extends ClientContainer implements javax.websocket.server.ServerContainer
{
    private ConcurrentHashMap<Class<?>, JsrServerMetadata> endpointServerMetadataCache = new ConcurrentHashMap<>();

    @Override
    public void addEndpoint(Class<?> endpointClass) throws DeploymentException
    {
        // TODO Auto-generated method stub
    }

    public void addEndpoint(JsrServerMetadata metadata)
    {
        // TODO Auto-generated method stub
    }

    @Override
    public void addEndpoint(ServerEndpointConfig serverConfig) throws DeploymentException
    {
        // TODO Auto-generated method stub
    }

    public JsrServerMetadata getServerEndpointMetadata(Class<?> endpointClass) throws DeploymentException
    {
        JsrServerMetadata basemetadata = endpointServerMetadataCache.get(endpointClass);
        if (basemetadata == null)
        {
            basemetadata = new JsrServerMetadata(this,endpointClass);
            AnnotatedEndpointScanner scanner = new AnnotatedEndpointScanner(basemetadata);
            scanner.scan();
            endpointServerMetadataCache.put(endpointClass,basemetadata);
        }

        return basemetadata;
    }
}
