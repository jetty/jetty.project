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

import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.common.events.EventDriverFactory;
import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.JsrSessionFactory;
import org.eclipse.jetty.websocket.jsr356.annotations.AnnotatedEndpointScanner;
import org.eclipse.jetty.websocket.jsr356.endpoints.JsrEndpointImpl;
import org.eclipse.jetty.websocket.jsr356.server.pathmap.WebSocketPathSpec;
import org.eclipse.jetty.websocket.server.MappedWebSocketCreator;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

public class ServerContainer extends ClientContainer implements javax.websocket.server.ServerContainer, WebSocketServerFactory.Listener
{
    public static ServerContainer get(WebAppContext context)
    {
        return (ServerContainer)context.getAttribute(WebSocketConfiguration.JAVAX_WEBSOCKET_SERVER_CONTAINER);
    }

    private final MappedWebSocketCreator mappedCreator;
    private WebSocketServerFactory webSocketServletFactory;
    private ConcurrentHashMap<Class<?>, JsrServerMetadata> endpointServerMetadataCache = new ConcurrentHashMap<>();

    public ServerContainer(MappedWebSocketCreator creator)
    {
        super();
        this.mappedCreator = creator;
    }

    @Override
    public void addEndpoint(Class<?> endpointClass) throws DeploymentException
    {
        JsrServerMetadata metadata = getServerEndpointMetadata(endpointClass);
        addEndpoint(metadata);
    }

    public void addEndpoint(JsrServerMetadata metadata) throws DeploymentException
    {
        addEndpoint(metadata.getConfig());
    }

    @Override
    public void addEndpoint(ServerEndpointConfig config) throws DeploymentException
    {
        JsrCreator creator = new JsrCreator(config);
        mappedCreator.addMapping(new WebSocketPathSpec(config.getPath()),creator);
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

    public WebSocketServletFactory getWebSocketServletFactory()
    {
        return webSocketServletFactory;
    }

    @Override
    public void onWebSocketServerFactoryStarted(WebSocketServerFactory factory)
    {
        this.webSocketServletFactory = factory;
        EventDriverFactory eventDriverFactory = this.webSocketServletFactory.getEventDriverFactory();
        eventDriverFactory.addImplementation(new JsrServerEndpointImpl(this));
        eventDriverFactory.addImplementation(new JsrEndpointImpl());
        this.webSocketServletFactory.setSessionFactory(new JsrSessionFactory(this));
    }

    @Override
    public void onWebSocketServerFactoryStopped(WebSocketServerFactory factory)
    {
        /* do nothing */
    }
}
