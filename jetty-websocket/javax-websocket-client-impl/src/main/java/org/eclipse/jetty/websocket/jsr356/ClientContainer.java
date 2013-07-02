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

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.client.io.UpgradeListener;
import org.eclipse.jetty.websocket.jsr356.annotations.AnnotatedEndpointScanner;
import org.eclipse.jetty.websocket.jsr356.client.EmptyClientEndpointConfig;
import org.eclipse.jetty.websocket.jsr356.client.JsrClientMetadata;
import org.eclipse.jetty.websocket.jsr356.endpoints.ConfiguredEndpoint;
import org.eclipse.jetty.websocket.jsr356.endpoints.JsrEventDriverFactory;

/**
 * Container for Client use of the javax.websocket API.
 * <p>
 * This should be specific to a JVM if run in a standalone mode. or specific to a WebAppContext if running on the Jetty server.
 */
public class ClientContainer extends CommonContainer
{
    private static final Logger LOG = Log.getLogger(ClientContainer.class);

    /** Tracking for all declared Client endpoints */
    private final ConcurrentHashMap<Class<?>, JsrClientMetadata> endpointClientMetadataCache;
    /** The jetty websocket client in use for this container */
    private WebSocketClient client;

    public ClientContainer()
    {
        super();
        endpointClientMetadataCache = new ConcurrentHashMap<>();
    }

    private Session connect(Object websocket, ClientEndpointConfig config, URI path) throws IOException
    {
        ClientEndpointConfig cec = config;
        if (cec == null)
        {
            // Create default config
            cec = ClientEndpointConfig.Builder.create().build();
        }
        ConfiguredEndpoint endpoint = new ConfiguredEndpoint(websocket,cec);
        ClientUpgradeRequest req = new ClientUpgradeRequest();
        UpgradeListener upgradeListener = null;

        if (cec != null)
        {
            for (Extension ext : cec.getExtensions())
            {
                req.addExtensions(new JsrExtensionConfig(ext));
            }

            if (cec.getPreferredSubprotocols().size() > 0)
            {
                req.setSubProtocols(config.getPreferredSubprotocols());
            }

            if (cec.getConfigurator() != null)
            {
                upgradeListener = new JsrUpgradeListener(cec.getConfigurator());
            }
        }

        Future<org.eclipse.jetty.websocket.api.Session> futSess = client.connect(endpoint,path,req,upgradeListener);
        try
        {
            return (JsrSession)futSess.get();
        }
        catch (InterruptedException | ExecutionException e)
        {
            throw new IOException("Connect failure",e);
        }
    }

    @Override
    public Session connectToServer(Class<? extends Endpoint> endpointClass, ClientEndpointConfig cec, URI path) throws DeploymentException, IOException
    {
        try
        {
            Object websocket = endpointClass.newInstance();
            return connect(websocket,cec,path);
        }
        catch (InstantiationException | IllegalAccessException e)
        {
            throw new DeploymentException("Unable to instantiate websocket: " + endpointClass,e);
        }
    }

    @Override
    public Session connectToServer(Class<?> annotatedEndpointClass, URI path) throws DeploymentException, IOException
    {
        try
        {
            ClientEndpoint anno = annotatedEndpointClass.getAnnotation(ClientEndpoint.class);
            if (anno != null)
            {
                // Annotated takes precedence here
                JsrClientMetadata metadata = new JsrClientMetadata(this,annotatedEndpointClass);
                Object websocket = annotatedEndpointClass.newInstance();
                return connect(websocket,metadata.getConfig(),path);
            }
            else if (Endpoint.class.isAssignableFrom(annotatedEndpointClass))
            {
                // Try if extends Endpoint (alternate use)
                Object websocket = annotatedEndpointClass.newInstance();
                ClientEndpointConfig cec = new EmptyClientEndpointConfig();
                return connect(websocket,cec,path);
            }
            else
            {
                StringBuilder err = new StringBuilder();
                err.append("Not a recognized websocket [");
                err.append(annotatedEndpointClass.getName());
                err.append("] does not extend @").append(ClientEndpoint.class.getName());
                err.append(" or extend from ").append(Endpoint.class.getName());
                throw new DeploymentException(err.toString());
            }
        }
        catch (InstantiationException | IllegalAccessException e)
        {
            throw new DeploymentException("Unable to instantiate websocket: " + annotatedEndpointClass,e);
        }
    }

    @Override
    public Session connectToServer(Endpoint endpointInstance, ClientEndpointConfig cec, URI path) throws DeploymentException, IOException
    {
        return connect(endpointInstance,cec,path);
    }

    @Override
    public Session connectToServer(Object annotatedEndpointInstance, URI path) throws DeploymentException, IOException
    {
        return connect(annotatedEndpointInstance,null,path);
    }

    public JsrClientMetadata getClientEndpointMetadata(Class<?> endpointClass) throws DeploymentException
    {
        JsrClientMetadata basemetadata = endpointClientMetadataCache.get(endpointClass);
        if (basemetadata == null)
        {
            basemetadata = new JsrClientMetadata(this,endpointClass);
            AnnotatedEndpointScanner scanner = new AnnotatedEndpointScanner(basemetadata);
            scanner.scan();
            endpointClientMetadataCache.put(endpointClass,basemetadata);
        }

        return basemetadata;
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

    @Override
    public Set<Session> getOpenSessions()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void setAsyncSendTimeout(long timeoutmillis)
    {
        client.setAsyncWriteTimeout(timeoutmillis);
    }

    @Override
    public void setDefaultMaxBinaryMessageBufferSize(int max)
    {
        // TODO: add safety net for policy assertions
        client.setMaxBinaryMessageBufferSize(max);
    }

    @Override
    public void setDefaultMaxSessionIdleTimeout(long timeout)
    {
        client.setMaxIdleTimeout(timeout);
    }

    @Override
    public void setDefaultMaxTextMessageBufferSize(int max)
    {
        // TODO: add safety net for policy assertions
        client.setMaxTextMessageBufferSize(max);
    }

    @Override
    public void start()
    {
        client = new WebSocketClient();
        client.setEventDriverFactory(new JsrEventDriverFactory(client.getPolicy(),this));
        client.setSessionFactory(new JsrSessionFactory(this));

        try
        {
            client.start();
        }
        catch (Exception e)
        {
            LOG.warn("Unable to start Jetty WebSocketClient instance",e);
        }
    }

    @Override
    public void stop()
    {
        try
        {
            client.stop();
        }
        catch (Exception e)
        {
            LOG.warn("Unable to start Jetty WebSocketClient instance",e);
        }
    }
}
