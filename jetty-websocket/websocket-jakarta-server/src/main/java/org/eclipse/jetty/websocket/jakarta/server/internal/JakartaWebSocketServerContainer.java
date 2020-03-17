//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.jakarta.server.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Function;
import jakarta.servlet.ServletContext;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.exception.WebSocketException;
import org.eclipse.jetty.websocket.jakarta.client.JakartaWebSocketClientContainer;
import org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.servlet.WebSocketMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedObject("JSR356 Server Container")
public class JakartaWebSocketServerContainer extends JakartaWebSocketClientContainer implements jakarta.websocket.server.ServerContainer, LifeCycle.Listener
{

    public static final String JAKARTA_WEBSOCKET_CONTAINER_ATTRIBUTE = jakarta.websocket.server.ServerContainer.class.getName();
    private static final Logger LOG = LoggerFactory.getLogger(JakartaWebSocketServerContainer.class);

    public static JakartaWebSocketServerContainer getContainer(ServletContext servletContext)
    {
        return (JakartaWebSocketServerContainer)servletContext.getAttribute(JAKARTA_WEBSOCKET_CONTAINER_ATTRIBUTE);
    }

    public static JakartaWebSocketServerContainer ensureContainer(ServletContext servletContext)
    {
        ContextHandler contextHandler = ServletContextHandler.getServletContextHandler(servletContext, "Jakarta Websocket");
        if (contextHandler.getServer() == null)
            throw new IllegalStateException("Server has not been set on the ServletContextHandler");

        JakartaWebSocketServerContainer container = getContainer(servletContext);
        if (container == null)
        {
            Function<WebSocketComponents, WebSocketCoreClient> coreClientSupplier = (wsComponents) ->
            {
                WebSocketCoreClient coreClient = (WebSocketCoreClient)servletContext.getAttribute(WebSocketCoreClient.WEBSOCKET_CORECLIENT_ATTRIBUTE);
                if (coreClient == null)
                {
                    // Find Pre-Existing (Shared?) HttpClient and/or executor
                    HttpClient httpClient = (HttpClient)servletContext.getAttribute(JakartaWebSocketServletContainerInitializer.HTTPCLIENT_ATTRIBUTE);
                    if (httpClient == null)
                        httpClient = (HttpClient)contextHandler.getServer().getAttribute(JakartaWebSocketServletContainerInitializer.HTTPCLIENT_ATTRIBUTE);

                    Executor executor = httpClient == null ? null : httpClient.getExecutor();
                    if (executor == null)
                        executor = (Executor)servletContext.getAttribute("org.eclipse.jetty.server.Executor");
                    if (executor == null)
                        executor = contextHandler.getServer().getThreadPool();

                    if (httpClient != null && httpClient.getExecutor() == null)
                        httpClient.setExecutor(executor);

                    // create the core client
                    coreClient = new WebSocketCoreClient(httpClient, wsComponents);
                    coreClient.getHttpClient().setName("Jakarta-WebSocketClient@" + Integer.toHexString(coreClient.getHttpClient().hashCode()));
                    if (executor != null && httpClient == null)
                        coreClient.getHttpClient().setExecutor(executor);
                    servletContext.setAttribute(WebSocketCoreClient.WEBSOCKET_CORECLIENT_ATTRIBUTE, coreClient);
                }
                return coreClient;
            };

            // Create the Jetty ServerContainer implementation
            container = new JakartaWebSocketServerContainer(
                WebSocketMapping.ensureMapping(servletContext, WebSocketMapping.DEFAULT_KEY),
                WebSocketComponents.ensureWebSocketComponents(servletContext),
                coreClientSupplier);
            contextHandler.addManaged(container);
            contextHandler.addEventListener(container);
        }
        // Store a reference to the ServerContainer per - jakarta.websocket spec 1.0 final - section 6.4: Programmatic Server Deployment
        servletContext.setAttribute(JAKARTA_WEBSOCKET_CONTAINER_ATTRIBUTE, container);
        return container;
    }

    private final WebSocketMapping webSocketMapping;
    private final JakartaWebSocketServerFrameHandlerFactory frameHandlerFactory;
    private List<Class<?>> deferredEndpointClasses;
    private List<ServerEndpointConfig> deferredEndpointConfigs;

    /**
     * Main entry point for {@link JakartaWebSocketServletContainerInitializer}.
     *
     * @param webSocketMapping the {@link WebSocketMapping} that this container belongs to
     */
    public JakartaWebSocketServerContainer(WebSocketMapping webSocketMapping)
    {
        this(webSocketMapping, new WebSocketComponents());
    }

    public JakartaWebSocketServerContainer(WebSocketMapping webSocketMapping, WebSocketComponents components)
    {
        super(components);
        this.webSocketMapping = webSocketMapping;
        this.frameHandlerFactory = new JakartaWebSocketServerFrameHandlerFactory(this);
    }

    /**
     * Main entry point for {@link JakartaWebSocketServletContainerInitializer}.
     *
     * @param webSocketMapping the {@link WebSocketMapping} that this container belongs to
     * @param components the {@link WebSocketComponents} instance to use
     * @param coreClientSupplier the supplier of the {@link WebSocketCoreClient} instance to use
     */
    public JakartaWebSocketServerContainer(WebSocketMapping webSocketMapping, WebSocketComponents components, Function<WebSocketComponents, WebSocketCoreClient> coreClientSupplier)
    {
        super(components, coreClientSupplier);
        this.webSocketMapping = webSocketMapping;
        this.frameHandlerFactory = new JakartaWebSocketServerFrameHandlerFactory(this);
    }

    @Override
    public void lifeCycleStopping(LifeCycle context)
    {
        ContextHandler contextHandler = (ContextHandler)context;
        JakartaWebSocketServerContainer container = contextHandler.getBean(JakartaWebSocketServerContainer.class);
        if (container == this)
        {
            contextHandler.removeBean(container);
            LifeCycle.stop(container);
        }
    }

    @Override
    public JakartaWebSocketServerFrameHandlerFactory getFrameHandlerFactory()
    {
        return frameHandlerFactory;
    }

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
                    throw new DeploymentException(String.format("Class must be @%s annotated: %s", ServerEndpoint.class.getName(), endpointClass.getName()));

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
    public void addEndpoint(ServerEndpointConfig providedConfig) throws DeploymentException
    {
        if (providedConfig == null)
            throw new DeploymentException("ServerEndpointConfig is null");

        if (isStarted() || isStarting())
        {
            Class<?> endpointClass = providedConfig.getEndpointClass();
            try
            {
                // If we have annotations merge the annotated ServerEndpointConfig with the provided one.
                ServerEndpoint anno = endpointClass.getAnnotation(ServerEndpoint.class);
                ServerEndpointConfig config = (anno == null) ? providedConfig
                    : new AnnotatedServerEndpointConfig(this, endpointClass, anno, providedConfig);

                if (LOG.isDebugEnabled())
                    LOG.debug("addEndpoint({}) path={} endpoint={}", config, config.getPath(), endpointClass);

                addEndpointMapping(config);
            }
            catch (WebSocketException e)
            {
                throw new DeploymentException("Unable to deploy: " + endpointClass.getName(), e);
            }
        }
        else
        {
            if (deferredEndpointConfigs == null)
                deferredEndpointConfigs = new ArrayList<>();
            deferredEndpointConfigs.add(providedConfig);
        }
    }

    private void addEndpointMapping(ServerEndpointConfig config) throws WebSocketException
    {
        frameHandlerFactory.getMetadata(config.getEndpointClass(), config);

        JakartaWebSocketCreator creator = new JakartaWebSocketCreator(this, config, getExtensionRegistry());

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
