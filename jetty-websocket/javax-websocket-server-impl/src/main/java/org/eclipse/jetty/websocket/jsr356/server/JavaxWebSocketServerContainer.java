//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.WebSocketContainerContext;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.jsr356.client.JavaxWebSocketClientContainer;
import org.eclipse.jetty.websocket.servlet.MappedWebSocketServletNegotiator;

@ManagedObject("JSR356 Server Container")
public class JavaxWebSocketServerContainer extends JavaxWebSocketClientContainer implements javax.websocket.server.ServerContainer
{
    private static final Logger LOG = Log.getLogger(JavaxWebSocketServerContainer.class);

    /**
     * Get the WebSocketContainer out of the current ThreadLocal reference
     * of the active ContextHandler.
     *
     * @return the WebSocketContainer if found, null if not found.
     */
    public static WebSocketContainer getWebSocketContainer()
    {
        ContextHandler.Context context = ContextHandler.getCurrentContext();
        if (context == null)
            return null;
        
        ContextHandler handler = ContextHandler.getContextHandler(context);
        if (handler == null)
            return null;
        
        if (!(handler instanceof ServletContextHandler))
            return null;
        
        return (javax.websocket.WebSocketContainer) handler.getServletContext().getAttribute("javax.websocket.server.ServerContainer");
    }
    
    private final WebSocketContainerContext containerContext;
    private final MappedWebSocketServletNegotiator mappedWebSocketServletNegotiator;
    private final JavaxWebSocketServerFrameHandlerFactory frameHandlerFactory;
    private List<Class<?>> deferredEndpointClasses;
    private List<ServerEndpointConfig> deferredEndpointConfigs;
    
    /**
     *
     * @param containerContext the {@link WebSocketContainerContext} to use
     * @param httpClient the {@link HttpClient} instance to use
     */
    public JavaxWebSocketServerContainer(WebSocketContainerContext containerContext, MappedWebSocketServletNegotiator mappedWebSocketServletNegotiator, HttpClient httpClient)
    {
        super(new WebSocketCoreClient(httpClient));
        this.containerContext = containerContext;
        this.mappedWebSocketServletNegotiator = mappedWebSocketServletNegotiator;
        this.frameHandlerFactory = new JavaxWebSocketServerFrameHandlerFactory(this);
    }

    @Override
    public ByteBufferPool getBufferPool()
    {
        return this.containerContext.getBufferPool();
    }

    @Override
    public ClassLoader getContextClassloader()
    {
        return this.containerContext.getContextClassloader();
    }

    @Override
    public Executor getExecutor()
    {
        return this.containerContext.getExecutor();
    }

    @Override
    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return this.containerContext.getExtensionRegistry();
    }

    @Override
    public JavaxWebSocketServerFrameHandlerFactory getFrameHandlerFactory()
    {
        return frameHandlerFactory;
    }

    @Override
    public DecoratedObjectFactory getObjectFactory()
    {
        return this.containerContext.getObjectFactory();
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return this.containerContext.getPolicy();
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
        frameHandlerFactory.createMetadata(config.getEndpointClass(), config);
        
        JavaxWebSocketCreator creator = new JavaxWebSocketCreator(this, config, this.containerContext.getExtensionRegistry());
        this.mappedWebSocketServletNegotiator.addMapping(new UriTemplatePathSpec(config.getPath()), creator);
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
        return this.containerContext.getPolicy().getAsyncWriteTimeout();
    }
    
    @Override
    public int getDefaultMaxBinaryMessageBufferSize()
    {
        return this.containerContext.getPolicy().getMaxBinaryMessageSize();
    }
    
    @Override
    public long getDefaultMaxSessionIdleTimeout()
    {
        return this.containerContext.getPolicy().getIdleTimeout();
    }
    
    @Override
    public int getDefaultMaxTextMessageBufferSize()
    {
        return this.containerContext.getPolicy().getMaxTextMessageSize();
    }
    
    @Override
    public void setAsyncSendTimeout(long ms)
    {
        super.setAsyncSendTimeout(ms);
        this.containerContext.getPolicy().setAsyncWriteTimeout(ms);
    }
    
    @Override
    public void setDefaultMaxBinaryMessageBufferSize(int max)
    {
        super.setDefaultMaxBinaryMessageBufferSize(max);
        // overall message limit (used in non-streaming)
        this.containerContext.getPolicy().setMaxBinaryMessageSize(max);
    }
    
    @Override
    public void setDefaultMaxSessionIdleTimeout(long ms)
    {
        super.setDefaultMaxSessionIdleTimeout(ms);
        this.containerContext.getPolicy().setIdleTimeout(ms);
    }
    
    @Override
    public void setDefaultMaxTextMessageBufferSize(int max)
    {
        super.setDefaultMaxTextMessageBufferSize(max);
        // overall message limit (used in non-streaming)
        this.containerContext.getPolicy().setMaxTextMessageSize(max);
    }
    
    @Override
    public Set<Session> getOpenSessions()
    {
        return new HashSet<>(getBeans(Session.class));
    }
}
