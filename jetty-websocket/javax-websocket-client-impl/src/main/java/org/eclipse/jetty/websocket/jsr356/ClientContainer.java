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
import org.eclipse.jetty.websocket.jsr356.annotations.AnnotatedEndpointScanner;
import org.eclipse.jetty.websocket.jsr356.endpoints.ConfiguredEndpoint;
import org.eclipse.jetty.websocket.jsr356.endpoints.JsrClientMetadata;
import org.eclipse.jetty.websocket.jsr356.endpoints.JsrEventDriverFactory;

public class ClientContainer implements ContainerService
{
    private static final Logger LOG = Log.getLogger(ClientContainer.class);
    private final DecoderMetadataFactory decoderMetadataFactory;
    private ConcurrentHashMap<Class<?>, JsrClientMetadata> endpointClientMetadataCache = new ConcurrentHashMap<>();
    private WebSocketClient client;

    public ClientContainer()
    {
        decoderMetadataFactory = new DecoderMetadataFactory();
    }

    private Session connect(Object websocket, ClientEndpointConfig config, URI path) throws IOException
    {
        ClientEndpointConfig cec = config;
        if (cec == null)
        {
            cec = ClientEndpointConfig.Builder.create().build();
        }

        ConfiguredEndpoint endpoint = new ConfiguredEndpoint(websocket,cec);
        ClientUpgradeRequest req = new ClientUpgradeRequest();
        if (config != null)
        {
            for (Extension ext : config.getExtensions())
            {
                req.addExtensions(new JsrExtensionConfig(ext));
            }

            if (config.getPreferredSubprotocols().size() > 0)
            {
                req.setSubProtocols(config.getPreferredSubprotocols());
            }
        }
        Future<org.eclipse.jetty.websocket.api.Session> futSess = client.connect(endpoint,path,req);
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
            Object websocket = annotatedEndpointClass.newInstance();
            return connect(websocket,null,path);
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
    public DecoderMetadataFactory getDecoderMetadataFactory()
    {
        return decoderMetadataFactory;
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
