//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.server;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.events.EventDriverFactory;
import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.JsrSessionFactory;
import org.eclipse.jetty.websocket.jsr356.annotations.AnnotatedEndpointScanner;
import org.eclipse.jetty.websocket.jsr356.endpoints.EndpointInstance;
import org.eclipse.jetty.websocket.jsr356.metadata.EndpointMetadata;
import org.eclipse.jetty.websocket.server.NativeWebSocketConfiguration;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;

public class ServerContainer extends ClientContainer implements javax.websocket.server.ServerContainer
{
    private static final Logger LOG = Log.getLogger(ServerContainer.class);

    private final NativeWebSocketConfiguration configuration;
    private List<Class<?>> deferredEndpointClasses;
    private List<ServerEndpointConfig> deferredEndpointConfigs;

    public ServerContainer(NativeWebSocketConfiguration configuration, Executor executor)
    {
        super(configuration.getFactory());
        this.configuration = configuration;
        EventDriverFactory eventDriverFactory = this.configuration.getFactory().getEventDriverFactory();
        eventDriverFactory.addImplementation(new JsrServerEndpointImpl());
        eventDriverFactory.addImplementation(new JsrServerExtendsEndpointImpl());
        this.configuration.getFactory().addSessionFactory(new JsrSessionFactory(this));
        addBean(this.configuration);
    }

    public EndpointInstance newClientEndpointInstance(Object endpoint, ServerEndpointConfig config, String path)
    {
        EndpointMetadata metadata = getClientEndpointMetadata(endpoint.getClass(),config);
        ServerEndpointConfig cec = config;
        if (config == null)
        {
            if (metadata instanceof AnnotatedServerEndpointMetadata)
            {
                cec = ((AnnotatedServerEndpointMetadata)metadata).getConfig();
            }
            else
            {
                cec = new BasicServerEndpointConfig(this,endpoint.getClass(),path);
            }
        }
        return new EndpointInstance(endpoint,cec,metadata);
    }

    @Override
    public void addEndpoint(Class<?> endpointClass) throws DeploymentException
    {
        if (isStarted() || isStarting())
        {
            ServerEndpointMetadata metadata = getServerEndpointMetadata(endpointClass,null);
            addEndpoint(metadata);
        }
        else
        {
            if (deferredEndpointClasses == null)
            {
                deferredEndpointClasses = new ArrayList<Class<?>>();
            }
            deferredEndpointClasses.add(endpointClass);
        }
    }

    private void addEndpoint(ServerEndpointMetadata metadata) throws DeploymentException
    {
        JsrCreator creator = new JsrCreator(this,metadata,this.configuration.getFactory().getExtensionFactory());
        this.configuration.addMapping(new UriTemplatePathSpec(metadata.getPath()),creator);
    }

    @Override
    public void addEndpoint(ServerEndpointConfig config) throws DeploymentException
    {
        if (isStarted() || isStarting())
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("addEndpoint({}) path={} endpoint={}",config,config.getPath(),config.getEndpointClass());
            }
            ServerEndpointMetadata metadata = getServerEndpointMetadata(config.getEndpointClass(),config);
            addEndpoint(metadata);
        }
        else
        {
            if (deferredEndpointConfigs == null)
            {
                deferredEndpointConfigs = new ArrayList<ServerEndpointConfig>();
            }
            deferredEndpointConfigs.add(config);
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

    public ServerEndpointMetadata getServerEndpointMetadata(final Class<?> endpoint, final ServerEndpointConfig config) throws DeploymentException
    {
        ServerEndpointMetadata metadata = null;

        ServerEndpoint anno = endpoint.getAnnotation(ServerEndpoint.class);
        if (anno != null)
        {
            // Annotated takes precedence here
            AnnotatedServerEndpointMetadata ametadata = new AnnotatedServerEndpointMetadata(this,endpoint,config);
            AnnotatedEndpointScanner<ServerEndpoint, ServerEndpointConfig> scanner = new AnnotatedEndpointScanner<>(ametadata);
            metadata = ametadata;
            scanner.scan();
        }
        else if (Endpoint.class.isAssignableFrom(endpoint))
        {
            // extends Endpoint
            @SuppressWarnings("unchecked")
            Class<? extends Endpoint> eendpoint = (Class<? extends Endpoint>)endpoint;
            metadata = new SimpleServerEndpointMetadata(eendpoint,config);
        }
        else
        {
            StringBuilder err = new StringBuilder();
            err.append("Not a recognized websocket [");
            err.append(endpoint.getName());
            err.append("] does not extend @").append(ServerEndpoint.class.getName());
            err.append(" or extend from ").append(Endpoint.class.getName());
            throw new DeploymentException("Unable to identify as valid Endpoint: " + endpoint);
        }

        return metadata;
    }
    
    @Override
    public long getDefaultAsyncSendTimeout()
    {
        return this.configuration.getPolicy().getAsyncWriteTimeout();
    }

    @Override
    public int getDefaultMaxBinaryMessageBufferSize()
    {
        return this.configuration.getPolicy().getMaxBinaryMessageSize();
    }

    @Override
    public long getDefaultMaxSessionIdleTimeout()
    {
        return this.configuration.getPolicy().getIdleTimeout();
    }

    @Override
    public int getDefaultMaxTextMessageBufferSize()
    {
        return this.configuration.getPolicy().getMaxTextMessageSize();
    }
    
    public WebSocketServerFactory getWebSocketServerFactory()
    {
        return this.configuration.getFactory();
    }

    @Override
    public void setAsyncSendTimeout(long ms)
    {
        super.setAsyncSendTimeout(ms);
        this.configuration.getPolicy().setAsyncWriteTimeout(ms);
    }

    @Override
    public void setDefaultMaxBinaryMessageBufferSize(int max)
    {
        super.setDefaultMaxBinaryMessageBufferSize(max);
        // overall message limit (used in non-streaming)
        this.configuration.getPolicy().setMaxBinaryMessageSize(max);
        // incoming streaming buffer size
        this.configuration.getPolicy().setMaxBinaryMessageBufferSize(max);
    }

    @Override
    public void setDefaultMaxSessionIdleTimeout(long ms)
    {
        super.setDefaultMaxSessionIdleTimeout(ms);
        this.configuration.getPolicy().setIdleTimeout(ms);
    }

    @Override
    public void setDefaultMaxTextMessageBufferSize(int max)
    {
        super.setDefaultMaxTextMessageBufferSize(max);
        // overall message limit (used in non-streaming)
        this.configuration.getPolicy().setMaxTextMessageSize(max);
        // incoming streaming buffer size
        this.configuration.getPolicy().setMaxTextMessageBufferSize(max);
    }

    @Override
    public void onSessionClosed(WebSocketSession session)
    {
        getWebSocketServerFactory().onSessionClosed(session);
    }

    @Override
    public void onSessionOpened(WebSocketSession session)
    {
        getWebSocketServerFactory().onSessionOpened(session);
    }

    @Override
    public Set<Session> getOpenSessions()
    {
        return new HashSet<>(getWebSocketServerFactory().getBeans(Session.class));
    }
}
