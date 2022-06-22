//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.jakarta.server.internal;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Function;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.ee9.nested.ContextHandler;
import org.eclipse.jetty.ee9.nested.HttpChannel;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.websocket.jakarta.client.internal.JakartaWebSocketClientContainer;
import org.eclipse.jetty.ee9.websocket.jakarta.server.config.ContainerDefaultConfigurator;
import org.eclipse.jetty.ee9.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.util.Blocking;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.exception.InvalidSignatureException;
import org.eclipse.jetty.websocket.core.internal.util.ReflectUtils;
import org.eclipse.jetty.websocket.core.server.Handshaker;
import org.eclipse.jetty.websocket.core.server.WebSocketMappings;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketServerComponents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedObject("JSR356 Server Container")
public class JakartaWebSocketServerContainer extends JakartaWebSocketClientContainer implements jakarta.websocket.server.ServerContainer, LifeCycle.Listener
{
    public static final String PATH_PARAM_ATTRIBUTE = "javax.websocket.server.pathParams";

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

        JakartaWebSocketServerContainer containerFromServletContext = getContainer(servletContext);
        if (containerFromServletContext != null)
            return containerFromServletContext;

        Function<WebSocketComponents, WebSocketCoreClient> coreClientSupplier = (wsComponents) ->
        {
            WebSocketCoreClient coreClient = (WebSocketCoreClient)servletContext.getAttribute(WebSocketCoreClient.WEBSOCKET_CORECLIENT_ATTRIBUTE);
            if (coreClient == null)
            {
                // Find Pre-Existing (Shared?) HttpClient and/or executor
                HttpClient httpClient = (HttpClient)servletContext.getAttribute(JakartaWebSocketServletContainerInitializer.HTTPCLIENT_ATTRIBUTE);
                if (httpClient == null)
                    httpClient = (HttpClient)contextHandler.getServer().getAttribute(JakartaWebSocketServletContainerInitializer.HTTPCLIENT_ATTRIBUTE);

                Executor executor = wsComponents.getExecutor();
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
        JakartaWebSocketServerContainer container = new JakartaWebSocketServerContainer(
            WebSocketMappings.ensureMappings(contextHandler.getCoreContextHandler()),
            WebSocketServerComponents.getWebSocketComponents(contextHandler.getCoreContextHandler()),
            coreClientSupplier);

        // Manage the lifecycle of the Container.
        contextHandler.addManaged(container);
        contextHandler.addEventListener(container);
        contextHandler.addEventListener(new LifeCycle.Listener()
        {
            @Override
            public void lifeCycleStopping(LifeCycle event)
            {
                servletContext.removeAttribute(JAKARTA_WEBSOCKET_CONTAINER_ATTRIBUTE);
                contextHandler.removeBean(container);
                contextHandler.removeEventListener(container);
                contextHandler.removeEventListener(this);
            }

            @Override
            public String toString()
            {
                return String.format("%sCleanupListener", JakartaWebSocketServerContainer.class.getSimpleName());
            }
        });

        // Store a reference to the ServerContainer per - jakarta.websocket spec 1.0 final - section 6.4: Programmatic Server Deployment
        servletContext.setAttribute(JAKARTA_WEBSOCKET_CONTAINER_ATTRIBUTE, container);
        return container;
    }

    private final WebSocketMappings webSocketMappings;
    private final JakartaWebSocketServerFrameHandlerFactory frameHandlerFactory;
    private List<Class<?>> deferredEndpointClasses;
    private List<ServerEndpointConfig> deferredEndpointConfigs;

    /**
     * Main entry point for {@link JakartaWebSocketServletContainerInitializer}.
     *
     * @param webSocketMappings the {@link WebSocketMappings} that this container belongs to
     * @param components the {@link WebSocketComponents} instance to use
     * @param coreClientSupplier the supplier of the {@link WebSocketCoreClient} instance to use
     */
    public JakartaWebSocketServerContainer(WebSocketMappings webSocketMappings, WebSocketComponents components, Function<WebSocketComponents, WebSocketCoreClient> coreClientSupplier)
    {
        super(components, coreClientSupplier);
        this.webSocketMappings = webSocketMappings;
        this.frameHandlerFactory = new JakartaWebSocketServerFrameHandlerFactory(this);
    }

    @Override
    public JakartaWebSocketServerFrameHandlerFactory getFrameHandlerFactory()
    {
        return frameHandlerFactory;
    }

    private void validateEndpointConfig(ServerEndpointConfig config) throws DeploymentException
    {
        if (config == null)
        {
            throw new DeploymentException("Unable to deploy null ServerEndpointConfig");
        }

        ServerEndpointConfig.Configurator configurator = config.getConfigurator();
        if (configurator == null)
        {
            throw new DeploymentException("Unable to deploy with null ServerEndpointConfig.Configurator");
        }

        Class<?> endpointClass = config.getEndpointClass();
        if (endpointClass == null)
        {
            throw new DeploymentException("Unable to deploy null endpoint class from ServerEndpointConfig: " + config.getClass().getName());
        }

        if (!Modifier.isPublic(endpointClass.getModifiers()))
        {
            throw new DeploymentException("Class is not public: " + endpointClass.getName());
        }

        if (configurator.getClass() == ContainerDefaultConfigurator.class)
        {
            if (!ReflectUtils.isDefaultConstructable(endpointClass))
            {
                throw new DeploymentException("Cannot access default constructor for the class: " + endpointClass.getName());
            }
        }
    }

    @Override
    public void addEndpoint(Class<?> endpointClass) throws DeploymentException
    {
        if (endpointClass == null)
        {
            throw new DeploymentException("Unable to deploy null endpoint class");
        }

        if (isStarted() || isStarting())
        {
            ServerEndpoint anno = endpointClass.getAnnotation(ServerEndpoint.class);
            if (anno == null)
            {
                throw new DeploymentException(String.format("Class must be @%s annotated: %s", ServerEndpoint.class.getName(), endpointClass.getName()));
            }

            if (LOG.isDebugEnabled())
            {
                LOG.debug("addEndpoint({})", endpointClass);
            }

            ServerEndpointConfig config = new AnnotatedServerEndpointConfig(this, endpointClass, anno);
            validateEndpointConfig(config);
            addEndpointMapping(config);
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
            // Decorate the provided Configurator.
            components.getObjectFactory().decorate(providedConfig.getConfigurator());

            // If we have annotations merge the annotated ServerEndpointConfig with the provided one.
            Class<?> endpointClass = providedConfig.getEndpointClass();
            ServerEndpoint anno = endpointClass.getAnnotation(ServerEndpoint.class);
            ServerEndpointConfig config = (anno == null) ? providedConfig
                : new AnnotatedServerEndpointConfig(this, endpointClass, anno, providedConfig);

            if (LOG.isDebugEnabled())
                LOG.debug("addEndpoint({}) path={} endpoint={}", config, config.getPath(), endpointClass);

            validateEndpointConfig(config);
            addEndpointMapping(config);
        }
        else
        {
            if (deferredEndpointConfigs == null)
                deferredEndpointConfigs = new ArrayList<>();
            deferredEndpointConfigs.add(providedConfig);
        }
    }

    private void addEndpointMapping(ServerEndpointConfig config) throws DeploymentException
    {
        try
        {
            frameHandlerFactory.getMetadata(config.getEndpointClass(), config);
            JakartaWebSocketCreator creator = new JakartaWebSocketCreator(this, config, getExtensionRegistry());
            PathSpec pathSpec = new UriTemplatePathSpec(config.getPath());
            webSocketMappings.addMapping(pathSpec, creator, frameHandlerFactory, defaultCustomizer);
        }
        catch (InvalidSignatureException e)
        {
            throw new DeploymentException(e.getMessage(), e);
        }
        catch (Throwable t)
        {
            throw new DeploymentException("Unable to deploy: " + config.getEndpointClass().getName(), t);
        }
    }

    public void upgradeHttpToWebSocket(Object httpServletRequest, Object httpServletResponse, ServerEndpointConfig sec,
                                       Map<String, String> pathParameters) throws IOException, DeploymentException
    {
        HttpServletRequest request = (HttpServletRequest)httpServletRequest;
        HttpServletResponse response = (HttpServletResponse)httpServletResponse;

        // Decorate the provided Configurator.
        components.getObjectFactory().decorate(sec.getConfigurator());

        // If we have annotations merge the annotated ServerEndpointConfig with the provided one.
        Class<?> endpointClass = sec.getEndpointClass();
        ServerEndpoint anno = endpointClass.getAnnotation(ServerEndpoint.class);
        ServerEndpointConfig config = (anno == null) ? sec
            : new AnnotatedServerEndpointConfig(this, endpointClass, anno, sec);

        if (LOG.isDebugEnabled())
            LOG.debug("addEndpoint({}) path={} endpoint={}", config, config.getPath(), endpointClass);

        validateEndpointConfig(config);
        frameHandlerFactory.getMetadata(config.getEndpointClass(), config);
        request.setAttribute(JakartaWebSocketServerContainer.PATH_PARAM_ATTRIBUTE, pathParameters);

        // Perform the upgrade.
        JakartaWebSocketCreator creator = new JakartaWebSocketCreator(this, config, getExtensionRegistry());
        WebSocketNegotiator negotiator = WebSocketNegotiator.from(creator, frameHandlerFactory);
        Handshaker handshaker = webSocketMappings.getHandshaker();

        HttpChannel httpChannel = (HttpChannel)request.getAttribute(HttpChannel.class.getName());
        try (Blocking.Callback callback = Blocking.callback())
        {
            handshaker.upgradeRequest(negotiator, httpChannel.getCoreRequest(), httpChannel.getCoreResponse(), callback, components, defaultCustomizer);
            callback.block();
        }
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
    protected void doClientStart()
    {
        // Do nothing.
    }

    @Override
    protected void doClientStop()
    {
        // Do nothing.
    }
}
