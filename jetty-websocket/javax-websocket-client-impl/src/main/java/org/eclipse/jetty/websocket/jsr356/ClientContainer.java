//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.websocket.ClientEndpoint;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.Extension;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.ShutdownThread;
import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.client.io.UpgradeListener;
import org.eclipse.jetty.websocket.jsr356.annotations.AnnotatedEndpointScanner;
import org.eclipse.jetty.websocket.jsr356.client.AnnotatedClientEndpointMetadata;
import org.eclipse.jetty.websocket.jsr356.client.EmptyClientEndpointConfig;
import org.eclipse.jetty.websocket.jsr356.client.SimpleEndpointMetadata;
import org.eclipse.jetty.websocket.jsr356.decoders.PrimitiveDecoderMetadataSet;
import org.eclipse.jetty.websocket.jsr356.encoders.PrimitiveEncoderMetadataSet;
import org.eclipse.jetty.websocket.jsr356.endpoints.EndpointInstance;
import org.eclipse.jetty.websocket.jsr356.endpoints.JsrEventDriverFactory;
import org.eclipse.jetty.websocket.jsr356.metadata.EndpointMetadata;

/**
 * Container for Client use of the javax.websocket API.
 * <p>
 * This should be specific to a JVM if run in a standalone mode. or specific to a WebAppContext if running on the Jetty server.
 */
public class ClientContainer extends ContainerLifeCycle implements WebSocketContainer
{
    /** Tracking all primitive decoders for the container */
    private final DecoderFactory decoderFactory;
    /** Tracking all primitive encoders for the container */
    private final EncoderFactory encoderFactory;

    /** Tracking for all declared Client endpoints */
    private final Map<Class<?>, EndpointMetadata> endpointClientMetadataCache;
    /** The jetty websocket client in use for this container */
    private final WebSocketClient client;

    public ClientContainer()
    {
        endpointClientMetadataCache = new ConcurrentHashMap<>();
        decoderFactory = new DecoderFactory(PrimitiveDecoderMetadataSet.INSTANCE);
        encoderFactory = new EncoderFactory(PrimitiveEncoderMetadataSet.INSTANCE);

        EmptyClientEndpointConfig empty = new EmptyClientEndpointConfig();
        decoderFactory.init(empty);
        encoderFactory.init(empty);

        client = new WebSocketClient();
        client.setEventDriverFactory(new JsrEventDriverFactory(client.getPolicy()));
        client.setSessionFactory(new JsrSessionFactory(this));
        addBean(client);
    }

    private Session connect(EndpointInstance instance, URI path) throws IOException
    {
        Objects.requireNonNull(instance,"EndpointInstance cannot be null");
        Objects.requireNonNull(path,"Path cannot be null");

        ClientEndpointConfig config = (ClientEndpointConfig)instance.getConfig();
        ClientUpgradeRequest req = new ClientUpgradeRequest();
        UpgradeListener upgradeListener = null;

        for (Extension ext : config.getExtensions())
        {
            req.addExtensions(new JsrExtensionConfig(ext));
        }

        if (config.getPreferredSubprotocols().size() > 0)
        {
            req.setSubProtocols(config.getPreferredSubprotocols());
        }

        if (config.getConfigurator() != null)
        {
            upgradeListener = new JsrUpgradeListener(config.getConfigurator());
        }

        Future<org.eclipse.jetty.websocket.api.Session> futSess = client.connect(instance,path,req,upgradeListener);
        try
        {
            return (JsrSession)futSess.get();
        }
        catch (InterruptedException e)
        {
            throw new IOException("Connect failure",e);
        }
        catch (ExecutionException e)
        {
            // Unwrap Actual Cause
            Throwable cause = e.getCause();

            if (cause instanceof IOException)
            {
                // Just rethrow
                throw (IOException)cause;
            }
            else
            {
                throw new IOException("Connect failure",cause);
            }
        }
    }

    @Override
    public Session connectToServer(Class<? extends Endpoint> endpointClass, ClientEndpointConfig config, URI path) throws DeploymentException, IOException
    {
        EndpointInstance instance = newClientEndpointInstance(endpointClass,config);
        return connect(instance,path);
    }

    @Override
    public Session connectToServer(Class<?> annotatedEndpointClass, URI path) throws DeploymentException, IOException
    {
        EndpointInstance instance = newClientEndpointInstance(annotatedEndpointClass,null);
        return connect(instance,path);
    }

    @Override
    public Session connectToServer(Endpoint endpoint, ClientEndpointConfig config, URI path) throws DeploymentException, IOException
    {
        EndpointInstance instance = newClientEndpointInstance(endpoint,config);
        return connect(instance,path);
    }

    @Override
    public Session connectToServer(Object endpoint, URI path) throws DeploymentException, IOException
    {
        EndpointInstance instance = newClientEndpointInstance(endpoint,null);
        return connect(instance,path);
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        ShutdownThread.register(client);
    }

    @Override
    protected void doStop() throws Exception
    {
        endpointClientMetadataCache.clear();
        ShutdownThread.deregister(client);
        super.doStop();
    }

    public EndpointMetadata getClientEndpointMetadata(Class<?> endpoint)
    {
        EndpointMetadata metadata = null;

        synchronized (endpointClientMetadataCache)
        {
            metadata = endpointClientMetadataCache.get(endpoint);

            if (metadata != null)
            {
                return metadata;
            }

            ClientEndpoint anno = endpoint.getAnnotation(ClientEndpoint.class);
            if (anno != null)
            {
                // Annotated takes precedence here
                AnnotatedClientEndpointMetadata annoMetadata = new AnnotatedClientEndpointMetadata(this,endpoint);
                AnnotatedEndpointScanner<ClientEndpoint, ClientEndpointConfig> scanner = new AnnotatedEndpointScanner<>(annoMetadata);
                scanner.scan();
                metadata = annoMetadata;
            }
            else if (Endpoint.class.isAssignableFrom(endpoint))
            {
                // extends Endpoint
                @SuppressWarnings("unchecked")
                Class<? extends Endpoint> eendpoint = (Class<? extends Endpoint>)endpoint;
                metadata = new SimpleEndpointMetadata(eendpoint);
            }
            else
            {
                StringBuilder err = new StringBuilder();
                err.append("Not a recognized websocket [");
                err.append(endpoint.getName());
                err.append("] does not extend @").append(ClientEndpoint.class.getName());
                err.append(" or extend from ").append(Endpoint.class.getName());
                throw new InvalidWebSocketException("Unable to identify as valid Endpoint: " + endpoint);
            }

            endpointClientMetadataCache.put(endpoint,metadata);
            return metadata;
        }
    }

    public DecoderFactory getDecoderFactory()
    {
        return decoderFactory;
    }

    @Override
    public long getDefaultAsyncSendTimeout()
    {
        return client.getAsyncWriteTimeout();
    }

    @Override
    public int getDefaultMaxBinaryMessageBufferSize()
    {
        return client.getMaxBinaryMessageBufferSize();
    }

    @Override
    public long getDefaultMaxSessionIdleTimeout()
    {
        return client.getMaxIdleTimeout();
    }

    @Override
    public int getDefaultMaxTextMessageBufferSize()
    {
        return client.getMaxTextMessageBufferSize();
    }

    public EncoderFactory getEncoderFactory()
    {
        return encoderFactory;
    }

    @Override
    public Set<Extension> getInstalledExtensions()
    {
        Set<Extension> ret = new HashSet<>();
        ExtensionFactory extensions = client.getExtensionFactory();

        for (String name : extensions.getExtensionNames())
        {
            ret.add(new JsrExtension(name));
        }

        return ret;
    }

    /**
     * Used in {@link Session#getOpenSessions()}
     */
    public Set<Session> getOpenSessions()
    {
        // TODO Auto-generated method stub
        return null;
    }

    private EndpointInstance newClientEndpointInstance(Class<?> endpointClass, ClientEndpointConfig config)
    {
        try
        {
            return newClientEndpointInstance(endpointClass.newInstance(),config);
        }
        catch (InstantiationException | IllegalAccessException e)
        {
            throw new InvalidWebSocketException("Unable to instantiate websocket: " + endpointClass.getClass());
        }
    }

    public EndpointInstance newClientEndpointInstance(Object endpoint, ClientEndpointConfig config)
    {
        EndpointMetadata metadata = getClientEndpointMetadata(endpoint.getClass());
        ClientEndpointConfig cec = config;
        if (config == null)
        {
            if (metadata instanceof AnnotatedClientEndpointMetadata)
            {
                cec = ((AnnotatedClientEndpointMetadata)metadata).getConfig();
            }
            else
            {
                cec = new EmptyClientEndpointConfig();
            }
        }
        return new EndpointInstance(endpoint,cec,metadata);
    }

    @Override
    public void setAsyncSendTimeout(long ms)
    {
        client.setAsyncWriteTimeout(ms);
    }

    @Override
    public void setDefaultMaxBinaryMessageBufferSize(int max)
    {
        // overall message limit (used in non-streaming)
        client.getPolicy().setMaxBinaryMessageSize(max);
        // incoming streaming buffer size
        client.setMaxBinaryMessageBufferSize(max);
    }

    @Override
    public void setDefaultMaxSessionIdleTimeout(long ms)
    {
        client.setMaxIdleTimeout(ms);
    }

    @Override
    public void setDefaultMaxTextMessageBufferSize(int max)
    {
        // overall message limit (used in non-streaming)
        client.getPolicy().setMaxTextMessageSize(max);
        // incoming streaming buffer size
        client.setMaxTextMessageBufferSize(max);
    }
}
