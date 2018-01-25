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

package org.eclipse.jetty.websocket.jsr356.client;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

import javax.websocket.ClientEndpoint;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Extension;
import javax.websocket.Session;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.websocket.core.InvalidWebSocketException;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.jsr356.ConfiguredEndpoint;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketContainer;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketExtensionConfig;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketFrameHandlerFactory;

/**
 * Container for Client use of the javax.websocket API.
 * <p>
 * This should be specific to a JVM if run in a standalone mode. or specific to a WebAppContext if running on the Jetty server.
 */
@ManagedObject("JSR356 Client Container")
public class JavaxWebSocketClientContainer extends JavaxWebSocketContainer implements javax.websocket.WebSocketContainer
{
    private final WebSocketCoreClient coreClient;
    private final JavaxWebSocketFrameHandlerFactory frameHandlerFactory;
    private ClassLoader contextClassLoader;
    private DecoratedObjectFactory objectFactory;
    private WebSocketExtensionRegistry extensionRegistry;

    public JavaxWebSocketClientContainer()
    {
        this(new WebSocketCoreClient());
        // We created WebSocketCoreClient, let lifecycle be managed by us
        addManaged(coreClient);
    }

    public JavaxWebSocketClientContainer(HttpClient httpClient)
    {
        this(new WebSocketCoreClient(httpClient));
        // We created WebSocketCoreClient, let lifecycle be managed by us
        addManaged(coreClient);
    }

    public JavaxWebSocketClientContainer(WebSocketCoreClient coreClient)
    {
        super(coreClient.getPolicy());
        this.coreClient = coreClient;
        this.contextClassLoader = this.getClass().getClassLoader();
        this.objectFactory = new DecoratedObjectFactory();
        this.extensionRegistry = new WebSocketExtensionRegistry();
        this.frameHandlerFactory = new JavaxWebSocketFrameHandlerFactory(this);
    }

    @Override
    public JavaxWebSocketFrameHandlerFactory getFrameHandlerFactory()
    {
        return frameHandlerFactory;
    }

    protected HttpClient getHttpClient()
    {
        return coreClient.getHttpClient();
    }

    /**
     * Connect to remote websocket endpoint
     *
     * @param upgradeRequest the upgrade request information
     * @return the future for the session, available on success of connect
     * @throws IOException if unable to connect
     */
    private CompletableFuture<Session> connect(ClientUpgradeRequestImpl upgradeRequest) throws IOException
    {
        coreClient.connect(upgradeRequest);
        return upgradeRequest.getFutureSession();
    }

    private Session connect(ConfiguredEndpoint configuredEndpoint, URI destURI) throws IOException
    {
        Objects.requireNonNull(configuredEndpoint, "WebSocket configured endpoint cannot be null");
        Objects.requireNonNull(destURI, "Destination URI cannot be null");

        ClientUpgradeRequestImpl upgradeRequest = new ClientUpgradeRequestImpl(this, coreClient, destURI, configuredEndpoint);

        EndpointConfig config = configuredEndpoint.getConfig();
        if (config != null && config instanceof ClientEndpointConfig)
        {
            ClientEndpointConfig clientEndpointConfig = (ClientEndpointConfig) config;

            JsrUpgradeListener jsrUpgradeListener = new JsrUpgradeListener(clientEndpointConfig.getConfigurator());
            upgradeRequest.addListener(jsrUpgradeListener);

            for (Extension ext : clientEndpointConfig.getExtensions())
            {
                if (!getExtensionRegistry().isAvailable(ext.getName()))
                {
                    throw new IllegalArgumentException("Requested extension [" + ext.getName() + "] is not installed");
                }
                upgradeRequest.addExtensions(new JavaxWebSocketExtensionConfig(ext));
            }

            if (clientEndpointConfig.getPreferredSubprotocols().size() > 0)
            {
                upgradeRequest.setSubProtocols(clientEndpointConfig.getPreferredSubprotocols());
            }
        }

        try
        {
            Future<Session> sessionFuture = connect(upgradeRequest);
            // TODO: apply connect timeouts here?
            return sessionFuture.get();
        }
        catch (IOException e)
        {
            // rethrow
            throw e;
        }
        catch (Exception e)
        {
            throw new IOException("Unable to connect to " + destURI, e);
        }
    }

    @Override
    public Session connectToServer(final Class<? extends Endpoint> endpointClass, final ClientEndpointConfig config, URI path) throws DeploymentException, IOException
    {
        ClientEndpointConfig clientEndpointConfig = config;
        if (clientEndpointConfig == null)
        {
            clientEndpointConfig = new EmptyClientEndpointConfig();
        }
        ConfiguredEndpoint instance = newConfiguredEndpoint(endpointClass, clientEndpointConfig);
        return connect(instance, path);
    }

    @Override
    public Session connectToServer(final Class<?> annotatedEndpointClass, final URI path) throws DeploymentException, IOException
    {
        ConfiguredEndpoint instance = newConfiguredEndpoint(annotatedEndpointClass, new EmptyClientEndpointConfig());
        return connect(instance, path);
    }

    @Override
    public Session connectToServer(final Endpoint endpoint, final ClientEndpointConfig config, final URI path) throws DeploymentException, IOException
    {
        ClientEndpointConfig clientEndpointConfig = config;
        if (clientEndpointConfig == null)
        {
            clientEndpointConfig = new EmptyClientEndpointConfig();
        }
        ConfiguredEndpoint instance = newConfiguredEndpoint(endpoint, clientEndpointConfig);
        return connect(instance, path);
    }

    @Override
    public Session connectToServer(Object endpoint, URI path) throws DeploymentException, IOException
    {
        ConfiguredEndpoint instance = newConfiguredEndpoint(endpoint, new EmptyClientEndpointConfig());
        return connect(instance, path);
    }

    @Override
    public long getDefaultMaxSessionIdleTimeout()
    {
        return getHttpClient().getIdleTimeout();
    }

    @Override
    public ByteBufferPool getBufferPool()
    {
        return getHttpClient().getByteBufferPool();
    }

    @Override
    public ClassLoader getContextClassloader()
    {
        return contextClassLoader;
    }

    @Override
    public Executor getExecutor()
    {
        return getHttpClient().getExecutor();
    }

    private ConfiguredEndpoint newConfiguredEndpoint(Class<?> endpointClass, EndpointConfig config)
    {
        try
        {
            return newConfiguredEndpoint(endpointClass.newInstance(), config);
        }
        catch (DeploymentException | InstantiationException | IllegalAccessException e)
        {
            throw new InvalidWebSocketException("Unable to instantiate websocket: " + endpointClass.getClass());
        }
    }

    public DecoratedObjectFactory getObjectFactory()
    {
        return objectFactory;
    }

    public ConfiguredEndpoint newConfiguredEndpoint(Object endpoint, EndpointConfig providedConfig) throws DeploymentException
    {
        EndpointConfig config = providedConfig;

        if (config == null)
        {
            config = newEmptyConfig(endpoint);
        }

        config = readAnnotatedConfig(endpoint, config);

        return new ConfiguredEndpoint(endpoint, config);
    }

    protected EndpointConfig newEmptyConfig(Object endpoint)
    {
        return new EmptyClientEndpointConfig();
    }

    protected EndpointConfig readAnnotatedConfig(Object endpoint, EndpointConfig config) throws DeploymentException
    {
        ClientEndpoint anno = endpoint.getClass().getAnnotation(ClientEndpoint.class);
        if (anno != null)
        {
            // Overwrite Config from Annotation
            // TODO: should we merge with provided config?
            return new AnnotatedClientEndpointConfig(anno);
        }
        return config;
    }

    @Override
    public void setAsyncSendTimeout(long ms)
    {
        // TODO: how?
    }

    @Override
    public void setDefaultMaxSessionIdleTimeout(long ms)
    {
        getHttpClient().setIdleTimeout(ms);
    }
}
