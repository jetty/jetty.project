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

package org.eclipse.jetty.websocket.jsr356.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.websocket.DeploymentException;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.WebSocketLocalEndpoint;
import org.eclipse.jetty.websocket.core.WebSocketSession;
import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.JsrSessionFactory;
import org.eclipse.jetty.websocket.jsr356.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.jsr356.encoders.AvailableEncoders;
import org.eclipse.jetty.websocket.servlet.impl.NativeWebSocketConfiguration;
import org.eclipse.jetty.websocket.servlet.impl.WebSocketServerFactory;

@ManagedObject("JSR356 Server Container")
public class ServerContainer extends ClientContainer implements javax.websocket.server.ServerContainer
{
    private static final Logger LOG = Log.getLogger(ServerContainer.class);
    
    /**
     * Get the WebSocketContainer out of the current ThreadLocal reference
     * of the active ContextHandler.
     *
     * @return the WebSocketContainer if found, null if not found.
     */
    public static WebSocketContainer getWebSocketContainer()
    {
        ContextHandler.Context context = ContextHandler.getCurrentContext();
        if (context == null)
            return null;
        
        ContextHandler handler = ContextHandler.getContextHandler(context);
        if (handler == null)
            return null;
        
        if (!(handler instanceof ServletContextHandler))
            return null;
        
        return (javax.websocket.WebSocketContainer) handler.getServletContext().getAttribute("javax.websocket.server.ServerContainer");
    }
    
    private final NativeWebSocketConfiguration configuration;
    private List<Class<?>> deferredEndpointClasses;
    private List<ServerEndpointConfig> deferredEndpointConfigs;
    
    /**
     * @deprecated use {@code ServerContainer(NativeWebSocketConfiguration, HttpClient)} instead
     */
    @Deprecated
    public ServerContainer(NativeWebSocketConfiguration configuration, Executor executor)
    {
        this(configuration, (HttpClient) null);
    }
    
    public ServerContainer(NativeWebSocketConfiguration configuration, HttpClient httpClient)
    {
        super(configuration.getFactory(), httpClient);
        this.configuration = configuration;
        this.configuration.getFactory().addSessionFactory(new JsrSessionFactory(this));
        addBean(this.configuration);
    }
    
    @Override
    protected EndpointConfig newEmptyConfig(Object endpoint)
    {
        return new UndefinedServerEndpointConfig(endpoint.getClass());
    }
    
    protected EndpointConfig readAnnotatedConfig(Object endpoint, EndpointConfig config) throws DeploymentException
    {
        ServerEndpoint anno = endpoint.getClass().getAnnotation(ServerEndpoint.class);
        if (anno != null)
        {
            // Overwrite Config from Annotation
            // TODO: should we merge with provided config?
            return new AnnotatedServerEndpointConfig(this, endpoint.getClass(), anno, config);
        }
        return config;
    }
    
    /**
     * Register a &#064;{@link ServerEndpoint} annotated endpoint class to
     * the server
     *
     * @param endpointClass the annotated endpoint class to add to the server
     * @throws DeploymentException if unable to deploy that endpoint class
     * @see javax.websocket.server.ServerContainer#addEndpoint(Class)
     */
    @Override
    public void addEndpoint(Class<?> endpointClass) throws DeploymentException
    {
        if (endpointClass == null)
        {
            throw new DeploymentException("EndpointClass is null");
        }
        
        if (isStarted() || isStarting())
        {
            ServerEndpoint anno = endpointClass.getAnnotation(ServerEndpoint.class);
            if (anno == null)
            {
                throw new DeploymentException(String.format("Class must be @%s annotated: %s",
                        ServerEndpoint.class.getName(), endpointClass.getName()));
            }
            
            ServerEndpointConfig config = new AnnotatedServerEndpointConfig(this, endpointClass, anno);
            addEndpointMapping(config);
        }
        else
        {
            if (deferredEndpointClasses == null)
            {
                deferredEndpointClasses = new ArrayList<>();
            }
            deferredEndpointClasses.add(endpointClass);
        }
    }

    /**
     * Register a ServerEndpointConfig to the server
     *
     * @param config the endpoint config to add
     * @throws DeploymentException if unable to deploy that endpoint class
     * @see javax.websocket.server.ServerContainer#addEndpoint(ServerEndpointConfig)
     */
    @Override
    public void addEndpoint(ServerEndpointConfig config) throws DeploymentException
    {
        if (config == null)
        {
            throw new DeploymentException("ServerEndpointConfig is null");
        }
        
        if (isStarted() || isStarting())
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("addEndpoint({}) path={} endpoint={}", config, config.getPath(), config.getEndpointClass());
            }
            addEndpointMapping(config);
        }
        else
        {
            if (deferredEndpointConfigs == null)
            {
                deferredEndpointConfigs = new ArrayList<>();
            }
            deferredEndpointConfigs.add(config);
        }
    }
    
    private void addEndpointMapping(ServerEndpointConfig config) throws DeploymentException
    {
        assertIsValidEndpoint(config);
        
        JsrCreator creator = new JsrCreator(this, config, this.configuration.getFactory().getExtensionFactory());
        this.configuration.addMapping(new UriTemplatePathSpec(config.getPath()), creator);
    }
    
    private void assertIsValidEndpoint(ServerEndpointConfig config) throws DeploymentException
    {
        WebSocketLocalEndpoint endpointFunctions = null;
        try
        {
            // Test that endpoint can be instantiated
            Object endpoint = config.getEndpointClass().newInstance();
            
            // Establish an WSLocalEndpoint to test validity of Endpoint declaration
            AvailableEncoders availableEncoders = new AvailableEncoders(config);
            AvailableDecoders availableDecoders = new AvailableDecoders(config);
            Map<String, String> pathParameters = new HashMap<>();
            
            // if any pathspec has a URI Template with variables, we should include them (as String value 0)
            // in the test for validity of the declared @OnMessage methods that use @PathParam annotation
            // We chose the default string "0" as that is the most reliably converted to a
            // Java Primitive during this initial "is valid" test (the real URITemplate value
            // is used when a real connection is established later)
            for (String variable : new UriTemplatePathSpec(config.getPath()).getVariables())
            {
                pathParameters.put(variable, "0");
            }
            
            endpointFunctions = newJsrEndpointFunction(endpoint, getPolicy(), availableEncoders, availableDecoders, pathParameters, config);
            endpointFunctions.start(); // this should trigger an exception if endpoint is invalid.
        }
        catch (InstantiationException e)
        {
            throw new DeploymentException("Unable to instantiate new instance of endpoint: " + config.getEndpointClass().getName(), e);
        }
        catch (IllegalAccessException e)
        {
            throw new DeploymentException("Unable access endpoint: " + config.getEndpointClass().getName(), e);
        }
        catch (Exception e)
        {
            throw new DeploymentException("Unable add endpoint: " + config.getEndpointClass().getName(), e);
        }
        finally
        {
            if (endpointFunctions != null)
            {
                try
                {
                    // Dispose of WSLocalEndpoint
                    endpointFunctions.stop();
                }
                catch (Exception ignore)
                {
                    // ignore
                }
            }
        }
    }
    
    @Override
    public WebSocketLocalEndpoint newJsrEndpointFunction(Object endpoint,
                                                         WebSocketPolicy sessionPolicy,
                                                         AvailableEncoders availableEncoders,
                                                         AvailableDecoders availableDecoders,
                                                         Map<String, String> pathParameters,
                                                         EndpointConfig config)
    {
        return new JsrServerEndpointFunctions(endpoint,
                sessionPolicy,
                getExecutor(),
                availableEncoders,
                availableDecoders,
                pathParameters,
                config);
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
