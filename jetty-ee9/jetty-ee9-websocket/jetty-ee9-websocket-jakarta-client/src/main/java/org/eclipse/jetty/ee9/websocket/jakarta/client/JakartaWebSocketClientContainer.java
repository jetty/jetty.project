//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.jakarta.client;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Extension;
import jakarta.websocket.Session;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.ee9.websocket.jakarta.client.internal.AnnotatedClientEndpointConfig;
import org.eclipse.jetty.ee9.websocket.jakarta.client.internal.BasicClientEndpointConfig;
import org.eclipse.jetty.ee9.websocket.jakarta.client.internal.JakartaClientUpgradeRequest;
import org.eclipse.jetty.ee9.websocket.jakarta.client.internal.JsrUpgradeListener;
import org.eclipse.jetty.ee9.websocket.jakarta.common.ConfiguredEndpoint;
import org.eclipse.jetty.ee9.websocket.jakarta.common.JakartaWebSocketContainer;
import org.eclipse.jetty.ee9.websocket.jakarta.common.JakartaWebSocketExtensionConfig;
import org.eclipse.jetty.ee9.websocket.jakarta.common.JakartaWebSocketFrameHandler;
import org.eclipse.jetty.ee9.websocket.jakarta.common.JakartaWebSocketFrameHandlerFactory;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.ShutdownThread;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.exception.InvalidWebSocketException;
import org.eclipse.jetty.websocket.core.exception.UpgradeException;
import org.eclipse.jetty.websocket.core.exception.WebSocketTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Container for Client use of the jakarta.websocket API.
 * <p>
 * This should be specific to a JVM if run in a standalone mode. or specific to a WebAppContext if running on the Jetty server.
 */
@ManagedObject("JSR356 Client Container")
public class JakartaWebSocketClientContainer extends JakartaWebSocketContainer implements jakarta.websocket.WebSocketContainer
{
    private static final Logger LOG = LoggerFactory.getLogger(JakartaWebSocketClientContainer.class);
    private static final Map<ClassLoader, ContainerLifeCycle> SHUTDOWN_MAP = new ConcurrentHashMap<>();

    public static void setShutdownContainer(ContainerLifeCycle container)
    {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        SHUTDOWN_MAP.compute(cl, (k, v) -> container);
        if (LOG.isDebugEnabled())
            LOG.debug("initialized shutdown map@{} to [{}={}]", SHUTDOWN_MAP.hashCode(), cl, container);
    }

    protected WebSocketCoreClient coreClient;
    protected Function<WebSocketComponents, WebSocketCoreClient> coreClientFactory;
    private final JakartaWebSocketClientFrameHandlerFactory frameHandlerFactory;

    public JakartaWebSocketClientContainer()
    {
        this(new WebSocketComponents());
    }

    /**
     * Create a {@link jakarta.websocket.WebSocketContainer} using the supplied
     * {@link HttpClient} for environments where you want to configure
     * SSL/TLS or Proxy behaviors.
     *
     * @param httpClient the HttpClient instance to use
     */
    public JakartaWebSocketClientContainer(final HttpClient httpClient)
    {
        this(new WebSocketComponents(), (components) -> new WebSocketCoreClient(httpClient, components));
    }

    public JakartaWebSocketClientContainer(WebSocketComponents components)
    {
        this(components, WebSocketCoreClient::new);
    }

    public JakartaWebSocketClientContainer(WebSocketComponents components, Function<WebSocketComponents, WebSocketCoreClient> coreClientFactory)
    {
        super(components);
        this.coreClientFactory = coreClientFactory;
        this.frameHandlerFactory = new JakartaWebSocketClientFrameHandlerFactory(this);
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
    private CompletableFuture<Session> connect(JakartaClientUpgradeRequest upgradeRequest)
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

                JakartaWebSocketFrameHandler frameHandler = (JakartaWebSocketFrameHandler)upgradeRequest.getFrameHandler();
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

    private Session connect(ConfiguredEndpoint configuredEndpoint, URI destURI) throws IOException, DeploymentException
    {
        if (configuredEndpoint == null)
            throw new DeploymentException("WebSocket configured endpoint cannot be null");
        if (destURI == null)
            throw new DeploymentException("Destination URI cannot be null");

        JakartaClientUpgradeRequest upgradeRequest = new JakartaClientUpgradeRequest(this, getWebSocketCoreClient(), destURI, configuredEndpoint);

        EndpointConfig config = configuredEndpoint.getConfig();
        if (config instanceof ClientEndpointConfig clientEndpointConfig)
        {
            JsrUpgradeListener jsrUpgradeListener = new JsrUpgradeListener(clientEndpointConfig.getConfigurator());
            upgradeRequest.addListener(jsrUpgradeListener);

            for (Extension ext : clientEndpointConfig.getExtensions())
            {
                upgradeRequest.addExtensions(new JakartaWebSocketExtensionConfig(ext));
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
            if (cause instanceof DeploymentException)
                throw (DeploymentException)cause;
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
    public JakartaWebSocketFrameHandlerFactory getFrameHandlerFactory()
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

        // Mechanism 1.
        // - When this class is used by a web app, and it is loaded from the server
        //   class-path, so ContextHandler can be seen from this class' ClassLoader.
        // - When this class is used on the server in embedded code, so the same
        //   ClassLoader can load both this class and ContextHandler.
        Object contextHandler = getContextHandler();
        if (contextHandler != null)
        {
            Container.addBean(contextHandler, this, true);
            if (LOG.isDebugEnabled())
                LOG.debug("{} registered for ContextHandler shutdown to {}", this, contextHandler);
            return;
        }

        // Mechanism 2.
        // - When this class is used by a web app, and it is loaded from the web app
        //   ClassLoader because all the necessary jars have been put in WEB-INF/lib.
        //   In this case the ContextHandler class cannot be loaded by this class'
        //   ClassLoader, and we rely on the JakartaWebSocketShutdownContainer.
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        ContainerLifeCycle container = SHUTDOWN_MAP.get(cl);
        if (container != null)
        {
            container.addManaged(this);
            if (LOG.isDebugEnabled())
                LOG.debug("{} registered for Context shutdown to {}", this, container);
            return;
        }

        // Mechanism 3.
        // - When this class is used on the client side.
        ShutdownThread.register(this);
        if (LOG.isDebugEnabled())
            LOG.debug("{} registered for JVM shutdown", this);
    }

    protected void doClientStop()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("doClientStop() {}", this);

        Object contextHandler = getContextHandler();
        if (contextHandler != null)
        {
            Container.unmanage(contextHandler, this);
            Container.removeBean(contextHandler, this);
            if (LOG.isDebugEnabled())
                LOG.debug("{} deregistered for ContextHandler shutdown from {}", this, contextHandler);
            return;
        }

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        ContainerLifeCycle container = SHUTDOWN_MAP.get(cl);
        if (container != null)
        {
            // As we are already stopping this instance, un-manage first
            // to avoid that removeBean() stops again this instance.
            container.unmanage(this);
            if (container.removeBean(this))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} deregistered for Context shutdown from {}", this, container);
                return;
            }
        }

        ShutdownThread.deregister(this);
        if (LOG.isDebugEnabled())
            LOG.debug("{} deregistered for JVM shutdown", this);
    }

    public Object getContextHandler()
    {
        try
        {
            return getClass().getClassLoader()
                .loadClass("org.eclipse.jetty.ee9.nested.ContextHandler")
                .getMethod("getCurrentContextHandler")
                .invoke(null);
        }
        catch (Throwable x)
        {
            return null;
        }
    }
}
