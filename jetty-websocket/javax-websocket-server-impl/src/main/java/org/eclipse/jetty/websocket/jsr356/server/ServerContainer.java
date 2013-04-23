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

import java.util.EnumSet;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.DispatcherType;
import javax.websocket.ContainerProvider;
import javax.websocket.DeploymentException;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.annotations.AnnotatedEndpointScanner;
import org.eclipse.jetty.websocket.jsr356.server.pathmap.WebSocketPathSpec;
import org.eclipse.jetty.websocket.server.MappedWebSocketCreator;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;

public class ServerContainer extends ClientContainer implements javax.websocket.server.ServerContainer
{
    private static final Logger LOG = Log.getLogger(ServerContainer.class);

    public static ServerContainer getInstance()
    {
        return (ServerContainer)ContainerProvider.getWebSocketContainer();
    }
    private ConcurrentHashMap<Class<?>, JsrServerMetadata> endpointServerMetadataCache = new ConcurrentHashMap<>();
    private MappedWebSocketCreator mappedCreator;

    public ServerContainer()
    {
        super();
        WebAppContext webapp = WebAppContext.getCurrentWebAppContext();
        if (webapp != null)
        {
            WebSocketUpgradeFilter filter = new WebSocketUpgradeFilter();
            FilterHolder fholder = new FilterHolder(filter);
            fholder.setName("Jetty_WebSocketUpgradeFilter");
            fholder.setDisplayName("WebSocket Upgrade Filter");
            String pathSpec = "/*";
            webapp.addFilter(fholder,pathSpec,EnumSet.of(DispatcherType.REQUEST));
            LOG.debug("Adding {} mapped to {} to {}",filter,pathSpec,webapp);
            mappedCreator = filter;
        }
        else
        {
            LOG.debug("No active WebAppContext detected");
        }
    }

    @Override
    public void addEndpoint(Class<?> endpointClass) throws DeploymentException
    {
        JsrServerMetadata metadata = getServerEndpointMetadata(endpointClass);
        addEndpoint(metadata);
    }

    public void addEndpoint(JsrServerMetadata metadata) throws DeploymentException
    {
        addEndpoint(metadata.getEndpointConfigCopy());
    }

    @Override
    public void addEndpoint(ServerEndpointConfig config) throws DeploymentException
    {
        JsrCreator creator = new JsrCreator(config);
        mappedCreator.addMapping(new WebSocketPathSpec(config.getPath()),creator);
    }

    public void addMappedCreator(MappedWebSocketCreator mappedCreator)
    {
        this.mappedCreator = mappedCreator;
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
