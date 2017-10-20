//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.function.Function;

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
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.InvalidWebSocketException;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.jsr356.ConfiguredEndpoint;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketContainer;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketExtensionConfig;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketLocalEndpoint;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketRemoteEndpoint;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketSession;
import org.eclipse.jetty.websocket.jsr356.io.JavaxWebSocketConnection;

/**
 * Container for Client use of the javax.websocket API.
 * <p>
 * This should be specific to a JVM if run in a standalone mode. or specific to a WebAppContext if running on the Jetty server.
 */
@ManagedObject("JSR356 Client Container")
public class ClientContainer extends JavaxWebSocketContainer implements javax.websocket.WebSocketContainer
{
    private static final Logger LOG = Log.getLogger(ClientContainer.class);

    /* The HttpClient */
    private final HttpClient httpClient;

    /* The Decorated ObjectFactory */
    private final DecoratedObjectFactory objectFactory;

    protected Function<JavaxWebSocketConnection, JavaxWebSocketSession<
            ? extends ClientContainer,
            ? extends JavaxWebSocketConnection,
            ? extends JavaxWebSocketLocalEndpoint,
            ? extends JavaxWebSocketRemoteEndpoint>> newSessionFunction =
            (connection) -> new JavaxWebSocketSession(ClientContainer.this, connection);

    public ClientContainer()
    {
        this(new HttpClient());
        addManaged(this.httpClient);
    }

    public ClientContainer(HttpClient httpClient)
    {
        this(WebSocketPolicy.newClientPolicy(), httpClient);
    }

    /**
     * This is the entry point for ServerContainer, via ServletContext.getAttribute(ServerContainer.class.getName())
     *
     * @param policy the WebSocketPolicy to use
     * @param httpClient the HttpClient instance to use
     */
    protected ClientContainer(final WebSocketPolicy policy, final HttpClient httpClient)
    {
        super(policy);

        // TODO: adjust policy behavior to CLIENT ?

        this.httpClient = httpClient;
        this.objectFactory = new DecoratedObjectFactory();

        // TODO: document system property
        String jsr356TrustAll = System.getProperty("org.eclipse.jetty.websocket.jsr356.ssl-trust-all");
        if (jsr356TrustAll != null)
        {
            boolean trustAll = Boolean.parseBoolean(jsr356TrustAll);
            if (LOG.isDebugEnabled())
            {
                LOG.debug("JSR356 ClientContainer SSL Trust-All: {}", trustAll);
            }
            this.httpClient.getSslContextFactory().setTrustAll(trustAll);
        }

        // TODO: document system property
        String connectTimeout = System.getProperty("org.eclipse.jetty.websocket.jsr356.connect-timeout");
        if (connectTimeout != null)
        {
            try
            {
                int timeout = Integer.parseInt(connectTimeout);
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("JSR356 ClientContainer Connect Timeout: {}", timeout);
                }
                this.httpClient.setConnectTimeout(timeout);
            }
            catch (NumberFormatException e)
            {
                LOG.warn("Invalid Connect Timeout: " + connectTimeout, e);
            }
        }
    }

    public JavaxWebSocketSession createSession(JavaxWebSocketClientConnection connection, Object endpointInstance)
    {
        JavaxWebSocketSession session = newSessionFunction.apply(connection);
        JavaxWebSocketLocalEndpoint localEndpoint = getLocalEndpointFactory().createLocalEndpoint(endpointInstance, session, getPolicy(), httpClient.getExecutor());
        JavaxWebSocketRemoteEndpoint remoteEndpoint = new JavaxWebSocketRemoteEndpoint(session);

        session.setWebSocketEndpoint(endpointInstance, localEndpoint.getPolicy(), localEndpoint, remoteEndpoint);
        return session;
    }

    protected HttpClient getHttpClient()
    {
        return httpClient;
    }

    public DecoratedObjectFactory getObjectFactory()
    {
        return objectFactory;
    }

    private Session connect(ConfiguredEndpoint instance, URI destURI) throws IOException
    {
        synchronized (this.httpClient)
        {
            if (!this.httpClient.isStarted())
            {
                try
                {
                    this.httpClient.start();
                }
                catch (Exception e)
                {
                    throw new RuntimeException("Unable to start Client", e);
                }
            }
        }

        Objects.requireNonNull(instance, "EndpointInstance cannot be null");
        Objects.requireNonNull(destURI, "Destination URI cannot be null");

        ClientEndpointConfig config = (ClientEndpointConfig) instance.getConfig();
        ClientUpgradeRequest req = new ClientUpgradeRequest(this, destURI);
        ClientUpgradeListener upgradeListener = null;

        for (Extension ext : config.getExtensions())
        {
            req.addExtensions(new JavaxWebSocketExtensionConfig(ext));
        }

        if (config.getPreferredSubprotocols().size() > 0)
        {
            req.setSubProtocols(config.getPreferredSubprotocols());
        }

        if (config.getConfigurator() != null)
        {
            upgradeListener = new JsrUpgradeListener(config.getConfigurator());
        }

        // Validate websocket URI
        if (!destURI.isAbsolute())
        {
            throw new IllegalArgumentException("WebSocket URI must be absolute");
        }

        if (StringUtil.isBlank(destURI.getScheme()))
        {
            throw new IllegalArgumentException("WebSocket URI must include a scheme");
        }

        String scheme = destURI.getScheme().toLowerCase(Locale.ENGLISH);
        if (("ws".equals(scheme) == false) && ("wss".equals(scheme) == false))
        {
            throw new IllegalArgumentException("WebSocket URI scheme only supports [ws] and [wss], not [" + scheme + "]");
        }


        // Validate Requested Extensions
        for (ExtensionConfig reqExt : req.getExtensions())
        {
            if (!getExtensionRegistry().isAvailable(reqExt.getName()))
            {
                throw new IllegalArgumentException("Requested extension [" + reqExt.getName() + "] is not installed");
            }
        }

        req.setWebSocket(instance);
        req.setUpgradeListener(upgradeListener);

        Future<JavaxWebSocketSession> futSess = req.sendAsync();
        try
        {
            // Block until answer received (or timeout)
            return futSess.get();
        }
        catch (InterruptedException e)
        {
            throw new IOException("Connect failure", e);
        }
        catch (ExecutionException e)
        {
            // Unwrap Actual Cause
            Throwable cause = e.getCause();

            if (cause instanceof IOException)
            {
                // Just rethrow
                throw (IOException) cause;
            }
            else
            {
                throw new IOException("Connect failure", cause);
            }
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
        return httpClient.getIdleTimeout();
    }

    @Override
    public ByteBufferPool getBufferPool()
    {
        return httpClient.getByteBufferPool();
    }

    @Override
    public Executor getExecutor()
    {
        return httpClient.getExecutor();
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
        httpClient.setIdleTimeout(ms);
    }
}
