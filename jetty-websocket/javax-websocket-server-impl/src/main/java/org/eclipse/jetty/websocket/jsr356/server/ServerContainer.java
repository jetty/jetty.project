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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.websocket.DeploymentException;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.JsrSessionFactory;
import org.eclipse.jetty.websocket.server.MappedWebSocketCreator;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;

public class ServerContainer extends ClientContainer implements javax.websocket.server.ServerContainer
{
    private static final Logger LOG = Log.getLogger(ServerContainer.class);
    
    private final MappedWebSocketCreator mappedCreator;
    private final WebSocketServerFactory webSocketServerFactory;
    private List<Class<?>> deferredEndpointClasses;
    private List<ServerEndpointConfig> deferredEndpointConfigs;
    
    public ServerContainer(MappedWebSocketCreator creator, WebSocketServerFactory factory, Executor executor)
    {
        super(factory);
        this.mappedCreator = creator;
        this.webSocketServerFactory = factory;
        this.webSocketServerFactory.addSessionFactory(new JsrSessionFactory(this));
        addBean(webSocketServerFactory);
    }
    
    @Override
    protected EndpointConfig newEmptyConfig(Object endpoint)
    {
        return new UndefinedServerEndpointConfig(endpoint.getClass());
    }
    
    protected EndpointConfig readAnnotatedConfig(Object endpoint, EndpointConfig config) throws DeploymentException
    {
        ServerEndpoint anno = endpoint.getClass().getAnnotation(ServerEndpoint.class);
        if (anno != null)
        {
            // Overwrite Config from Annotation
            // TODO: should we merge with provided config?
            return new AnnotatedServerEndpointConfig(this, endpoint.getClass(), anno, config);
        }
        return config;
    }
    
    /**
     * Register a &#064;{@link ServerEndpoint} annotated endpoint class to
     * the server
     *
     * @param endpointClass the annotated endpoint class to add to the server
     * @throws DeploymentException if unable to deploy that endpoint class
     * @see javax.websocket.server.ServerContainer#addEndpoint(Class)
     */
    @Override
    public void addEndpoint(Class<?> endpointClass) throws DeploymentException
    {
        if (endpointClass == null)
        {
            throw new DeploymentException("EndpointClass is null");
        }
        
        if (isStarted() || isStarting())
        {
            ServerEndpoint anno = endpointClass.getAnnotation(ServerEndpoint.class);
            if (anno == null)
            {
                throw new DeploymentException(String.format("Class must be @%s annotated: %s",
                        ServerEndpoint.class.getName(), endpointClass.getName()));
            }
            
            ServerEndpointConfig config = new AnnotatedServerEndpointConfig(this, endpointClass, anno);
            addEndpointMapping(config);
        }
        else
        {
            if (deferredEndpointClasses == null)
            {
                deferredEndpointClasses = new ArrayList<>();
            }
            deferredEndpointClasses.add(endpointClass);
        }
    }
    
    /**
     * Register a ServerEndpointConfig to the server
     *
     * @param config the endpoint config to add
     * @throws DeploymentException if unable to deploy that endpoint class
     * @see javax.websocket.server.ServerContainer#addEndpoint(ServerEndpointConfig)
     */
    @Override
    public void addEndpoint(ServerEndpointConfig config) throws DeploymentException
    {
        if (config == null)
        {
            throw new DeploymentException("ServerEndpointConfig is null");
        }
        
        if (isStarted() || isStarting())
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("addEndpoint({}) path={} endpoint={}", config, config.getPath(), config.getEndpointClass());
            }
            addEndpointMapping(config);
        }
        else
        {
            if (deferredEndpointConfigs == null)
            {
                deferredEndpointConfigs = new ArrayList<>();
            }
            deferredEndpointConfigs.add(config);
        }
    }
    
    private void addEndpointMapping(ServerEndpointConfig config) throws DeploymentException
    {
        JsrCreator creator = new JsrCreator(this, config, webSocketServerFactory.getExtensionFactory());
        mappedCreator.addMapping(new UriTemplatePathSpec(config.getPath()), creator);
    }
    
    @Override
    protected void doStart() throws Exception
    {
        // Proceed with Normal Startup
        super.doStart();
        
        // Process Deferred Endpoints
        if (deferredEndpointClasses != null)
        {
            for (Class<?> endpointClass : deferredEndpointClasses)
            {
                addEndpoint(endpointClass);
            }
            deferredEndpointClasses.clear();
        }
        
        if (deferredEndpointConfigs != null)
        {
            for (ServerEndpointConfig config : deferredEndpointConfigs)
            {
                addEndpoint(config);
            }
            deferredEndpointConfigs.clear();
        }
    }
    
    @Override
    public long getDefaultAsyncSendTimeout()
    {
        return webSocketServerFactory.getPolicy().getAsyncWriteTimeout();
    }
    
    @Override
    public int getDefaultMaxBinaryMessageBufferSize()
    {
        return webSocketServerFactory.getPolicy().getMaxBinaryMessageSize();
    }
    
    @Override
    public long getDefaultMaxSessionIdleTimeout()
    {
        return webSocketServerFactory.getPolicy().getIdleTimeout();
    }
    
    @Override
    public int getDefaultMaxTextMessageBufferSize()
    {
        return webSocketServerFactory.getPolicy().getMaxTextMessageSize();
    }
    
    @Override
    public void setAsyncSendTimeout(long ms)
    {
        super.setAsyncSendTimeout(ms);
        webSocketServerFactory.getPolicy().setAsyncWriteTimeout(ms);
    }
    
    @Override
    public void setDefaultMaxBinaryMessageBufferSize(int max)
    {
        super.setDefaultMaxBinaryMessageBufferSize(max);
        // overall message limit (used in non-streaming)
        webSocketServerFactory.getPolicy().setMaxBinaryMessageSize(max);
        // incoming streaming buffer size
        webSocketServerFactory.getPolicy().setMaxBinaryMessageBufferSize(max);
    }
    
    @Override
    public void setDefaultMaxSessionIdleTimeout(long ms)
    {
        super.setDefaultMaxSessionIdleTimeout(ms);
        webSocketServerFactory.getPolicy().setIdleTimeout(ms);
    }
    
    @Override
    public void setDefaultMaxTextMessageBufferSize(int max)
    {
        super.setDefaultMaxTextMessageBufferSize(max);
        // overall message limit (used in non-streaming)
        webSocketServerFactory.getPolicy().setMaxTextMessageSize(max);
        // incoming streaming buffer size
        webSocketServerFactory.getPolicy().setMaxTextMessageBufferSize(max);
    }
    
    @Override
    public void onSessionClosed(WebSocketSession session)
    {
        webSocketServerFactory.onSessionClosed(session);
    }
    
    @Override
    public void onSessionOpened(WebSocketSession session)
    {
        webSocketServerFactory.onSessionOpened(session);
    }
    
    @Override
    public Set<Session> getOpenSessions()
    {
        return new HashSet<>(webSocketServerFactory.getBeans(Session.class));
    }
}
