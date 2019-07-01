//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import javax.servlet.ServletContext;
import javax.websocket.DeploymentException;
import javax.websocket.EndpointConfig;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.WebSocketException;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.javax.client.JavaxWebSocketClientContainer;
import org.eclipse.jetty.websocket.javax.server.internal.AnnotatedServerEndpointConfig;
import org.eclipse.jetty.websocket.javax.server.internal.JavaxWebSocketCreator;
import org.eclipse.jetty.websocket.javax.server.internal.UndefinedServerEndpointConfig;
import org.eclipse.jetty.websocket.servlet.WebSocketMapping;

@ManagedObject("JSR356 Server Container")
public class JavaxWebSocketServerContainer extends JavaxWebSocketClientContainer implements javax.websocket.server.ServerContainer, LifeCycle.Listener
{
    public static final String JAVAX_WEBSOCKET_CONTAINER_ATTRIBUTE = javax.websocket.server.ServerContainer.class.getName();
    private static final Logger LOG = Log.getLogger(JavaxWebSocketServerContainer.class);

    public static JavaxWebSocketServerContainer getContainer(ServletContext servletContext)
    {
        return (JavaxWebSocketServerContainer)servletContext.getAttribute(JAVAX_WEBSOCKET_CONTAINER_ATTRIBUTE);
    }

    public static JavaxWebSocketServerContainer ensureContainer(ServletContext servletContext)
    {
        ContextHandler contextHandler = ServletContextHandler.getServletContextHandler(servletContext, "Javax Websocket");
        if (contextHandler.getServer() == null)
            throw new IllegalStateException("Server has not been set on the ServletContextHandler");

        JavaxWebSocketServerContainer container = getContainer(servletContext);
        if (container == null)
        {
            Supplier<WebSocketCoreClient> coreClientSupplier = () ->
            {
                WebSocketCoreClient coreClient = (WebSocketCoreClient)servletContext.getAttribute(WebSocketCoreClient.WEBSOCKET_CORECLIENT_ATTRIBUTE);
                if (coreClient == null)
                {
                    // Find Pre-Existing (Shared?) HttpClient and/or executor
                    HttpClient httpClient = (HttpClient)servletContext.getAttribute(JavaxWebSocketServletContainerInitializer.HTTPCLIENT_ATTRIBUTE);
                    if (httpClient == null)
                        httpClient = (HttpClient)contextHandler.getServer().getAttribute(JavaxWebSocketServletContainerInitializer.HTTPCLIENT_ATTRIBUTE);

                    Executor executor = httpClient == null ? null : httpClient.getExecutor();
                    if (executor == null)
                        executor = (Executor)servletContext.getAttribute("org.eclipse.jetty.server.Executor");
                    if (executor == null)
                        executor = contextHandler.getServer().getThreadPool();

                    if (httpClient != null && httpClient.getExecutor() == null)
                        httpClient.setExecutor(executor);

                    // create the core client
                    coreClient = new WebSocketCoreClient(httpClient, WebSocketComponents.ensureWebSocketComponents(servletContext));
                    coreClient.getHttpClient().setName("Javax-WebSocketClient@" + Integer.toHexString(coreClient.getHttpClient().hashCode()));
                    if (executor != null && httpClient == null)
                        coreClient.getHttpClient().setExecutor(executor);
                    servletContext.setAttribute(WebSocketCoreClient.WEBSOCKET_CORECLIENT_ATTRIBUTE, coreClient);
                }
                return coreClient;
            };

            // Create the Jetty ServerContainer implementation
            container = new JavaxWebSocketServerContainer(
                WebSocketMapping.ensureMapping(servletContext, WebSocketMapping.DEFAULT_KEY),
                WebSocketComponents.ensureWebSocketComponents(servletContext),
                coreClientSupplier);
            contextHandler.addManaged(container);
            contextHandler.addLifeCycleListener(container);
        }
        // Store a reference to the ServerContainer per - javax.websocket spec 1.0 final - section 6.4: Programmatic Server Deployment
        servletContext.setAttribute(JAVAX_WEBSOCKET_CONTAINER_ATTRIBUTE, container);
        return container;
    }

    private final WebSocketMapping webSocketMapping;
    private final JavaxWebSocketServerFrameHandlerFactory frameHandlerFactory;
    private List<Class<?>> deferredEndpointClasses;
    private List<ServerEndpointConfig> deferredEndpointConfigs;

    /**
     * Main entry point for {@link JavaxWebSocketServletContainerInitializer}.
     *
     * @param webSocketMapping the {@link WebSocketMapping} that this container belongs to
     */
    public JavaxWebSocketServerContainer(WebSocketMapping webSocketMapping)
    {
        this(webSocketMapping, new WebSocketComponents());
    }

    public JavaxWebSocketServerContainer(WebSocketMapping webSocketMapping, WebSocketComponents components)
    {
        super(components);
        this.webSocketMapping = webSocketMapping;
        this.frameHandlerFactory = new JavaxWebSocketServerFrameHandlerFactory(this);
    }

    /**
     * Main entry point for {@link JavaxWebSocketServletContainerInitializer}.
     *
     * @param webSocketMapping the {@link WebSocketMapping} that this container belongs to
     * @param components the {@link WebSocketComponents} instance to use
     * @param coreClientSupplier the supplier of the {@link WebSocketCoreClient} instance to use
     */
    public JavaxWebSocketServerContainer(WebSocketMapping webSocketMapping, WebSocketComponents components, Supplier<WebSocketCoreClient> coreClientSupplier)
    {
        super(components, coreClientSupplier);
        this.webSocketMapping = webSocketMapping;
        this.frameHandlerFactory = new JavaxWebSocketServerFrameHandlerFactory(this);
    }

    @Override
    public void lifeCycleStopping(LifeCycle context)
    {
        ContextHandler contextHandler = (ContextHandler)context;
        JavaxWebSocketServerContainer container = contextHandler.getBean(JavaxWebSocketServerContainer.class);
        if (container == this)
        {
            contextHandler.removeBean(container);
            LifeCycle.stop(container);
        }
    }

    @Override
    public JavaxWebSocketServerFrameHandlerFactory getFrameHandlerFactory()
    {
        return frameHandlerFactory;
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
            catch (WebSocketException e)
            {
                throw new DeploymentException("Unable to deploy: " + endpointClass.getName(), e);
            }
        }
        else
        {
            if (deferredEndpointClasses == null)
                deferredEndpointClasses = new ArrayList<>();
            deferredEndpointClasses.add(endpointClass);
        }
    }

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

            try
            {
                addEndpointMapping(config);
            }
            catch (WebSocketException e)
            {
                throw new DeploymentException("Unable to deploy: " + config.getEndpointClass().getName(), e);
            }
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

    private void addEndpointMapping(ServerEndpointConfig config) throws WebSocketException
    {
        frameHandlerFactory.getMetadata(config.getEndpointClass(), config);

        JavaxWebSocketCreator creator = new JavaxWebSocketCreator(this, config, getExtensionRegistry());

        PathSpec pathSpec = new UriTemplatePathSpec(config.getPath());
        webSocketMapping.addMapping(pathSpec, creator, frameHandlerFactory, defaultCustomizer);
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
}
