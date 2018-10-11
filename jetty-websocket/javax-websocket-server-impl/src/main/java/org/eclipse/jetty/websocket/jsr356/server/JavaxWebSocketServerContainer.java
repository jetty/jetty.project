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

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.jsr356.InvalidWebSocketException;
import org.eclipse.jetty.websocket.jsr356.client.JavaxWebSocketClientContainer;
import org.eclipse.jetty.websocket.jsr356.server.internal.AnnotatedServerEndpointConfig;
import org.eclipse.jetty.websocket.jsr356.server.internal.JavaxWebSocketCreator;
import org.eclipse.jetty.websocket.jsr356.server.internal.UndefinedServerEndpointConfig;
import org.eclipse.jetty.websocket.servlet.internal.WebSocketServletFactoryImpl;

import javax.websocket.DeploymentException;
import javax.websocket.EndpointConfig;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

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

    private final WebSocketServletFactoryImpl webSocketServletFactory;
    private final JavaxWebSocketServerFrameHandlerFactory frameHandlerFactory;
    private final Executor executor;
    private long asyncSendTimeout = -1;
    private List<Class<?>> deferredEndpointClasses;
    private List<ServerEndpointConfig> deferredEndpointConfigs;

    /**
     * Main entry point for {@link JavaxWebSocketServerContainerInitializer}.
     *
     * @param webSocketServletFactory the {@link org.eclipse.jetty.websocket.servlet.WebSocketServletFactory} that this container belongs to
     * @param httpClient the {@link HttpClient} instance to use
     */
    public JavaxWebSocketServerContainer(WebSocketServletFactoryImpl webSocketServletFactory, HttpClient httpClient, Executor executor)
    {
        super(new WebSocketCoreClient(httpClient));
        this.coreClient.addBean(httpClient, false);
        this.webSocketServletFactory = webSocketServletFactory;
        this.executor = executor;
        this.frameHandlerFactory = new JavaxWebSocketServerFrameHandlerFactory(this);
    }

    @Override
    public ByteBufferPool getBufferPool()
    {
        return this.webSocketServletFactory.getBufferPool();
    }

    @Override
    public Executor getExecutor()
    {
        return this.executor;
    }

    @Override
    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return this.webSocketServletFactory.getExtensionRegistry();
    }

    @Override
    public JavaxWebSocketServerFrameHandlerFactory getFrameHandlerFactory()
    {
        return frameHandlerFactory;
    }

    @Override
    public DecoratedObjectFactory getObjectFactory()
    {
        return this.webSocketServletFactory.getObjectFactory();
    }

    @Override
    protected WebSocketCoreClient getWebSocketCoreClient() throws Exception
    {
        // Lazy Start Http Client
        if(!coreClient.getHttpClient().isStarted())
        {
            coreClient.getHttpClient().start();
        }

        // Lazy Start WebSocket Client
        if (!coreClient.isStarted())
        {
            coreClient.start();
        }

        return coreClient;
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
            try
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
            catch (InvalidWebSocketException e)
            {
                throw new DeploymentException("Unable to deploy: " + endpointClass.getName(), e);
            }
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
    
    private void addEndpointMapping(ServerEndpointConfig config)
    {
        frameHandlerFactory.createMetadata(config.getEndpointClass(), config);

        JavaxWebSocketCreator creator = new JavaxWebSocketCreator(this, config, this.webSocketServletFactory.getExtensionRegistry());
        this.webSocketServletFactory.addMapping(new UriTemplatePathSpec(config.getPath()), creator);
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
        return this.asyncSendTimeout;
    }
    
    @Override
    public int getDefaultMaxBinaryMessageBufferSize()
    {
        // TODO: warn on long -> int conversion issue
        return (int) this.webSocketServletFactory.getDefaultMaxBinaryMessageSize();
    }
    
    @Override
    public long getDefaultMaxSessionIdleTimeout()
    {
        return this.webSocketServletFactory.getDefaultIdleTimeout().toMillis();
    }
    
    @Override
    public int getDefaultMaxTextMessageBufferSize()
    {
        // TODO: warn on long -> int conversion issue
        return (int) this.webSocketServletFactory.getDefaultMaxTextMessageSize();
    }
    
    @Override
    public void setAsyncSendTimeout(long ms)
    {
        this.asyncSendTimeout = ms;
    }
    
    @Override
    public void setDefaultMaxBinaryMessageBufferSize(int max)
    {
        this.webSocketServletFactory.setDefaultMaxBinaryMessageSize(max);
    }
    
    @Override
    public void setDefaultMaxSessionIdleTimeout(long ms)
    {
        this.webSocketServletFactory.setDefaultIdleTimeout(Duration.ofMillis(ms));
    }
    
    @Override
    public void setDefaultMaxTextMessageBufferSize(int max)
    {
        this.webSocketServletFactory.setDefaultMaxTextMessageSize(max);
    }
}
