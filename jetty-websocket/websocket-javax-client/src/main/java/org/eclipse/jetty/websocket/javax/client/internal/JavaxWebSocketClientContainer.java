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

package org.eclipse.jetty.websocket.javax.client.internal;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.websocket.ClientEndpoint;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Extension;
import javax.websocket.Session;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.ShutdownThread;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.exception.InvalidWebSocketException;
import org.eclipse.jetty.websocket.core.exception.UpgradeException;
import org.eclipse.jetty.websocket.core.exception.WebSocketTimeoutException;
import org.eclipse.jetty.websocket.javax.common.ConfiguredEndpoint;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketContainer;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketExtensionConfig;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketFrameHandler;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketFrameHandlerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Container for Client use of the javax.websocket API.
 * <p>
 * This should be specific to a JVM if run in a standalone mode. or specific to a WebAppContext if running on the Jetty server.
 */
@ManagedObject("JSR356 Client Container")
public class JavaxWebSocketClientContainer extends JavaxWebSocketContainer implements javax.websocket.WebSocketContainer
{
    private static final Logger LOG = LoggerFactory.getLogger(JavaxWebSocketClientContainer.class);
    private static final AtomicReference<ContainerLifeCycle> SHUTDOWN_CONTAINER = new AtomicReference<>();

    public static void setShutdownContainer(ContainerLifeCycle container)
    {
        SHUTDOWN_CONTAINER.set(container);
        if (LOG.isDebugEnabled())
            LOG.debug("initialized {} to {}", String.format("%s@%x", SHUTDOWN_CONTAINER.getClass().getSimpleName(), SHUTDOWN_CONTAINER.hashCode()), container);
    }

    protected WebSocketCoreClient coreClient;
    protected Function<WebSocketComponents, WebSocketCoreClient> coreClientFactory;
    private final JavaxWebSocketClientFrameHandlerFactory frameHandlerFactory;

    public JavaxWebSocketClientContainer()
    {
        this(new WebSocketComponents());
    }

    /**
     * Create a {@link javax.websocket.WebSocketContainer} using the supplied
     * {@link HttpClient} for environments where you want to configure
     * SSL/TLS or Proxy behaviors.
     *
     * @param httpClient the HttpClient instance to use
     */
    public JavaxWebSocketClientContainer(final HttpClient httpClient)
    {
        this(new WebSocketComponents(), (components) -> new WebSocketCoreClient(httpClient, components));
    }

    public JavaxWebSocketClientContainer(WebSocketComponents components)
    {
        this(components, WebSocketCoreClient::new);
    }

    public JavaxWebSocketClientContainer(WebSocketComponents components, Function<WebSocketComponents, WebSocketCoreClient> coreClientFactory)
    {
        super(components);
        this.coreClientFactory = coreClientFactory;
        this.frameHandlerFactory = new JavaxWebSocketClientFrameHandlerFactory(this);
    }

    protected HttpClient getHttpClient()
    {
        return getWebSocketCoreClient().getHttpClient();
    }

    protected WebSocketCoreClient getWebSocketCoreClient()
    {
        if (coreClient == null)
        {
            coreClient = coreClientFactory.apply(components);
            addManaged(coreClient);
        }

        return coreClient;
    }

    /**
     * Connect to remote websocket endpoint
     *
     * @param upgradeRequest the upgrade request information
     * @return the future for the session, available on success of connect
     */
    private CompletableFuture<Session> connect(JavaxClientUpgradeRequest upgradeRequest)
    {
        upgradeRequest.setConfiguration(defaultCustomizer);
        CompletableFuture<Session> futureSession = new CompletableFuture<>();

        try
        {
            WebSocketCoreClient coreClient = getWebSocketCoreClient();
            coreClient.connect(upgradeRequest).whenComplete((coreSession, error) ->
            {
                if (error != null)
                {
                    futureSession.completeExceptionally(convertCause(error));
                    return;
                }

                JavaxWebSocketFrameHandler frameHandler = (JavaxWebSocketFrameHandler)upgradeRequest.getFrameHandler();
                futureSession.complete(frameHandler.getSession());
            });
        }
        catch (Exception e)
        {
            futureSession.completeExceptionally(e);
        }

        return futureSession;
    }

    public static Throwable convertCause(Throwable error)
    {
        if (error instanceof UpgradeException ||
            error instanceof WebSocketTimeoutException)
            return new IOException(error);

        if (error instanceof InvalidWebSocketException)
            return new DeploymentException(error.getMessage(), error);

        return error;
    }

    private Session connect(ConfiguredEndpoint configuredEndpoint, URI destURI) throws IOException
    {
        Objects.requireNonNull(configuredEndpoint, "WebSocket configured endpoint cannot be null");
        Objects.requireNonNull(destURI, "Destination URI cannot be null");

        JavaxClientUpgradeRequest upgradeRequest = new JavaxClientUpgradeRequest(this, getWebSocketCoreClient(), destURI, configuredEndpoint);

        EndpointConfig config = configuredEndpoint.getConfig();
        if (config instanceof ClientEndpointConfig)
        {
            ClientEndpointConfig clientEndpointConfig = (ClientEndpointConfig)config;

            JsrUpgradeListener jsrUpgradeListener = new JsrUpgradeListener(clientEndpointConfig.getConfigurator());
            upgradeRequest.addListener(jsrUpgradeListener);

            for (Extension ext : clientEndpointConfig.getExtensions())
            {
                upgradeRequest.addExtensions(new JavaxWebSocketExtensionConfig(ext));
            }

            if (clientEndpointConfig.getPreferredSubprotocols().size() > 0)
                upgradeRequest.setSubProtocols(clientEndpointConfig.getPreferredSubprotocols());
        }

        long timeout = getWebSocketCoreClient().getHttpClient().getConnectTimeout();
        try
        {
            Future<Session> sessionFuture = connect(upgradeRequest);
            if (timeout > 0)
                return sessionFuture.get(timeout + 1000, TimeUnit.MILLISECONDS);
            return sessionFuture.get();
        }
        catch (ExecutionException e)
        {
            var cause = e.getCause();
            if (cause instanceof RuntimeException)
                throw (RuntimeException)cause;
            if (cause instanceof IOException)
                throw (IOException)cause;
            throw new IOException(cause);
        }
        catch (TimeoutException e)
        {
            throw new IOException("Connection future timeout " + timeout + " ms for " + destURI, e);
        }
        catch (Throwable e)
        {
            throw new IOException("Unable to connect to " + destURI, e);
        }
    }

    @Override
    public Session connectToServer(final Class<? extends Endpoint> endpointClass, final ClientEndpointConfig providedConfig, URI path) throws DeploymentException, IOException
    {
        return connectToServer(newEndpoint(endpointClass), providedConfig, path);
    }

    @Override
    public Session connectToServer(final Class<?> annotatedEndpointClass, final URI path) throws DeploymentException, IOException
    {
        return connectToServer(newEndpoint(annotatedEndpointClass), path);
    }

    @Override
    public Session connectToServer(final Endpoint endpoint, final ClientEndpointConfig providedConfig, final URI path) throws DeploymentException, IOException
    {
        ClientEndpointConfig config;
        if (providedConfig == null)
        {
            config = new BasicClientEndpointConfig();
        }
        else
        {
            config = providedConfig;
            components.getObjectFactory().decorate(providedConfig.getConfigurator());
        }

        ConfiguredEndpoint instance = new ConfiguredEndpoint(endpoint, config);
        return connect(instance, path);
    }

    @Override
    public Session connectToServer(Object endpoint, URI path) throws DeploymentException, IOException
    {
        // The Configurator will be decorated when it is created in the getAnnotatedConfig method.
        ClientEndpointConfig config = getAnnotatedConfig(endpoint);
        ConfiguredEndpoint instance = new ConfiguredEndpoint(endpoint, config);
        return connect(instance, path);
    }

    @Override
    public JavaxWebSocketFrameHandlerFactory getFrameHandlerFactory()
    {
        return frameHandlerFactory;
    }

    @Override
    public Executor getExecutor()
    {
        return getHttpClient().getExecutor();
    }

    private <T> T newEndpoint(Class<T> endpointClass) throws DeploymentException
    {
        try
        {
            return endpointClass.getConstructor().newInstance();
        }
        catch (Throwable e)
        {
            throw new DeploymentException("Unable to instantiate websocket: " + endpointClass.getName());
        }
    }

    private ClientEndpointConfig getAnnotatedConfig(Object endpoint) throws DeploymentException
    {
        ClientEndpoint anno = endpoint.getClass().getAnnotation(ClientEndpoint.class);
        if (anno == null)
            throw new DeploymentException("Could not get ClientEndpoint annotation for " + endpoint.getClass().getName());

        return new AnnotatedClientEndpointConfig(anno, components);
    }

    @Override
    protected void doStart() throws Exception
    {
        doClientStart();
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        doClientStop();
    }

    protected void doClientStart()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("doClientStart() {}", this);

        // If we are running in Jetty register shutdown with the ContextHandler.
        if (addToContextHandler())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Shutdown registered with ContextHandler");
            return;
        }

        // If we are running inside a different ServletContainer we can register with the SHUTDOWN_CONTAINER static.
        ContainerLifeCycle shutdownContainer = SHUTDOWN_CONTAINER.get();
        if (shutdownContainer != null)
        {
            shutdownContainer.addManaged(this);
            if (LOG.isDebugEnabled())
                LOG.debug("Shutdown registered with ShutdownContainer {}", shutdownContainer);
            return;
        }

        ShutdownThread.register(this);
        if (LOG.isDebugEnabled())
            LOG.debug("Shutdown registered with ShutdownThread");
    }

    protected void doClientStop()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("doClientStop() {}", this);

        // Remove from context handler if running in Jetty server.
        removeFromContextHandler();

        // Remove from the Shutdown Container.
        ContainerLifeCycle shutdownContainer = SHUTDOWN_CONTAINER.get();
        if (shutdownContainer != null && shutdownContainer.contains(this))
        {
            // Un-manage first as we don't want to call stop again while in STOPPING state.
            shutdownContainer.unmanage(this);
            shutdownContainer.removeBean(this);
        }

        // If not running in a server we need to de-register with the shutdown thread.
        ShutdownThread.deregister(this);
    }

    private boolean addToContextHandler()
    {
        try
        {
            Object context = getClass().getClassLoader()
                .loadClass("org.eclipse.jetty.server.handler.ContextHandler")
                .getMethod("getCurrentContext")
                .invoke(null);

            Object contextHandler = context.getClass()
                .getMethod("getContextHandler")
                .invoke(context);

            contextHandler.getClass()
                .getMethod("addManaged", LifeCycle.class)
                .invoke(contextHandler, this);

            return true;
        }
        catch (Throwable throwable)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("error from addToContextHandler() for {}", this, throwable);
            return false;
        }
    }

    private void removeFromContextHandler()
    {
        try
        {
            Object context = getClass().getClassLoader()
                .loadClass("org.eclipse.jetty.server.handler.ContextHandler")
                .getMethod("getCurrentContext")
                .invoke(null);

            Object contextHandler = context.getClass()
                .getMethod("getContextHandler")
                .invoke(context);

            // Un-manage first as we don't want to call stop again while in STOPPING state.
            contextHandler.getClass()
                .getMethod("unmanage", Object.class)
                .invoke(contextHandler, this);

            contextHandler.getClass()
                .getMethod("removeBean", Object.class)
                .invoke(contextHandler, this);
        }
        catch (Throwable throwable)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("error from removeFromContextHandler() for {}", this, throwable);
        }
    }
}
