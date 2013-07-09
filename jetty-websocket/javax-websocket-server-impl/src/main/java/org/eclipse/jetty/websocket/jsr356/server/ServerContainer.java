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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.common.events.EventDriverFactory;
import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.JsrSessionFactory;
import org.eclipse.jetty.websocket.jsr356.annotations.AnnotatedEndpointScanner;
import org.eclipse.jetty.websocket.jsr356.endpoints.EndpointInstance;
import org.eclipse.jetty.websocket.jsr356.endpoints.JsrEndpointImpl;
import org.eclipse.jetty.websocket.jsr356.metadata.EndpointMetadata;
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
    private Map<Class<?>, ServerEndpointMetadata> endpointServerMetadataCache = new ConcurrentHashMap<>();

    public ServerContainer(MappedWebSocketCreator creator)
    {
        super();
        this.mappedCreator = creator;
    }

    public EndpointInstance newClientEndpointInstance(Object endpoint, ServerEndpointConfig config, String path)
    {
        EndpointMetadata metadata = getClientEndpointMetadata(endpoint.getClass());
        ServerEndpointConfig cec = config;
        if (config == null)
        {
            if (metadata instanceof AnnotatedServerEndpointMetadata)
            {
                cec = ((AnnotatedServerEndpointMetadata)metadata).getConfig();
            }
            else
            {
                cec = new EmptyServerEndpointConfig(endpoint.getClass(),path);
            }
        }
        return new EndpointInstance(endpoint,cec,metadata);
    }

    @Override
    public void addEndpoint(Class<?> endpointClass) throws DeploymentException
    {
        ServerEndpointMetadata metadata = getServerEndpointMetadata(endpointClass,null);
        addEndpoint(metadata);
    }

    public void addEndpoint(ServerEndpointMetadata metadata) throws DeploymentException
    {
        JsrCreator creator = new JsrCreator(metadata);
        mappedCreator.addMapping(new WebSocketPathSpec(metadata.getPath()),creator);
    }

    @Override
    public void addEndpoint(ServerEndpointConfig config) throws DeploymentException
    {
        ServerEndpointMetadata metadata = getServerEndpointMetadata(config.getEndpointClass(),config);
        addEndpoint(metadata);
    }

    public ServerEndpointMetadata getServerEndpointMetadata(Class<?> endpoint, ServerEndpointConfig config) throws DeploymentException
    {
        synchronized (endpointServerMetadataCache)
        {
            ServerEndpointMetadata metadata = endpointServerMetadataCache.get(endpoint);
            if (metadata != null)
            {
                return metadata;
            }

            ServerEndpoint anno = endpoint.getAnnotation(ServerEndpoint.class);
            if (anno != null)
            {
                // Annotated takes precedence here
                AnnotatedServerEndpointMetadata ametadata = new AnnotatedServerEndpointMetadata(this,endpoint);
                AnnotatedEndpointScanner<ServerEndpoint,ServerEndpointConfig> scanner = new AnnotatedEndpointScanner<>(ametadata);
                metadata = ametadata;
                scanner.scan();
            }
            else if (Endpoint.class.isAssignableFrom(endpoint))
            {
                // extends Endpoint
                @SuppressWarnings("unchecked")
                Class<? extends Endpoint> eendpoint = (Class<? extends Endpoint>)endpoint;
                metadata = new SimpleServerEndpointMetadata(eendpoint,config);
            }
            else
            {
                StringBuilder err = new StringBuilder();
                err.append("Not a recognized websocket [");
                err.append(endpoint.getName());
                err.append("] does not extend @").append(ServerEndpoint.class.getName());
                err.append(" or extend from ").append(Endpoint.class.getName());
                throw new DeploymentException("Unable to identify as valid Endpoint: " + endpoint);
            }

            endpointServerMetadataCache.put(endpoint,metadata);

            return metadata;
        }
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
        eventDriverFactory.addImplementation(new JsrServerEndpointImpl());
        eventDriverFactory.addImplementation(new JsrEndpointImpl());
        this.webSocketServletFactory.setSessionFactory(new JsrSessionFactory(this));
    }

    @Override
    public void onWebSocketServerFactoryStopped(WebSocketServerFactory factory)
    {
        /* do nothing */
    }
}
