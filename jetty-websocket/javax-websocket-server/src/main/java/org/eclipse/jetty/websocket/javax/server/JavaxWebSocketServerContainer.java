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

package org.eclipse.jetty.websocket.javax.server;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import javax.websocket.DeploymentException;
import javax.websocket.EndpointConfig;
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
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.javax.client.JavaxWebSocketClientContainer;
import org.eclipse.jetty.websocket.javax.common.InvalidWebSocketException;
import org.eclipse.jetty.websocket.javax.server.internal.AnnotatedServerEndpointConfig;
import org.eclipse.jetty.websocket.javax.server.internal.JavaxWebSocketCreator;
import org.eclipse.jetty.websocket.javax.server.internal.UndefinedServerEndpointConfig;
import org.eclipse.jetty.websocket.servlet.WebSocketCreatorMapping;

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

        return (javax.websocket.WebSocketContainer)handler.getServletContext().getAttribute("javax.websocket.server.ServerContainer");
    }

    private final WebSocketCreatorMapping _webSocketCreatorMapping;
    private final JavaxWebSocketServerFrameHandlerFactory frameHandlerFactory;
    private final Executor executor;
    private long asyncSendTimeout = -1;
    private List<Class<?>> deferredEndpointClasses;
    private List<ServerEndpointConfig> deferredEndpointConfigs;

    /**
     * Main entry point for {@link JavaxWebSocketServerContainerInitializer}.
     * @param webSocketCreatorMapping the {@link WebSocketCreatorMapping} that this container belongs to
     * @param httpClient              the {@link HttpClient} instance to use
     */
    public JavaxWebSocketServerContainer(WebSocketCreatorMapping webSocketCreatorMapping, HttpClient httpClient, Executor executor)
    {
        super(() ->
        {
            // TODO Can the client share the websocket or container buffer pool
            WebSocketCoreClient client = new WebSocketCoreClient(httpClient);
            if (executor != null && httpClient == null)
                client.getHttpClient().setExecutor(executor);

            return client;
        });
        this._webSocketCreatorMapping = webSocketCreatorMapping;
        this.executor = executor;
        this.frameHandlerFactory = new JavaxWebSocketServerFrameHandlerFactory(this);
    }

    @Override
    public ByteBufferPool getBufferPool()
    {
        return this._webSocketCreatorMapping.getBufferPool();
    }

    @Override
    public Executor getExecutor()
    {
        return this.executor;
    }

    @Override
    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return this._webSocketCreatorMapping.getExtensionRegistry();
    }

    @Override
    public JavaxWebSocketServerFrameHandlerFactory getFrameHandlerFactory()
    {
        return frameHandlerFactory;
    }

    @Override
    public DecoratedObjectFactory getObjectFactory()
    {
        return this._webSocketCreatorMapping.getObjectFactory();
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
        frameHandlerFactory.getMetadata(config.getEndpointClass(), config);

        JavaxWebSocketCreator creator = new JavaxWebSocketCreator(this, config, this._webSocketCreatorMapping.getExtensionRegistry());
        this._webSocketCreatorMapping.addMapping(new UriTemplatePathSpec(config.getPath()), creator);
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
        return (int)this._webSocketCreatorMapping.getDefaultMaxBinaryMessageSize();
    }

    @Override
    public long getDefaultMaxSessionIdleTimeout()
    {
        return this._webSocketCreatorMapping.getDefaultIdleTimeout().toMillis();
    }

    @Override
    public int getDefaultMaxTextMessageBufferSize()
    {
        // TODO: warn on long -> int conversion issue
        return (int)this._webSocketCreatorMapping.getDefaultMaxTextMessageSize();
    }

    @Override
    public void setAsyncSendTimeout(long ms)
    {
        this.asyncSendTimeout = ms;
    }

    @Override
    public void setDefaultMaxBinaryMessageBufferSize(int max)
    {
        this._webSocketCreatorMapping.setDefaultMaxBinaryMessageSize(max);
    }

    @Override
    public void setDefaultMaxSessionIdleTimeout(long ms)
    {
        this._webSocketCreatorMapping.setDefaultIdleTimeout(Duration.ofMillis(ms));
    }

    @Override
    public void setDefaultMaxTextMessageBufferSize(int max)
    {
        this._webSocketCreatorMapping.setDefaultMaxTextMessageSize(max);
    }
}
