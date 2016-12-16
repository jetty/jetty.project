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

package org.eclipse.jetty.websocket.server;

import java.io.IOException;

import javax.servlet.ServletContext;

import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.RegexPathSpec;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;

/**
 * Interface for Configuring Jetty Server Native WebSockets
 * <p>
 * Only applicable if using {@link WebSocketUpgradeFilter}
 * </p>
 */
public class NativeWebSocketConfiguration extends ContainerLifeCycle implements Dumpable
{
    private final WebSocketServerFactory factory;
    private final PathMappings<WebSocketCreator> mappings = new PathMappings<>();
    
    public NativeWebSocketConfiguration(ServletContext context)
    {
        this(new WebSocketServerFactory(context));
    }
    
    public NativeWebSocketConfiguration(WebSocketServerFactory webSocketServerFactory)
    {
        this.factory = webSocketServerFactory;
        addBean(this.factory);
    }
    
    @Override
    public void doStop() throws Exception
    {
        mappings.removeIf((mapped) -> !(mapped.getResource() instanceof PersistedWebSocketCreator));
        super.doStop();
    }
    
    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }
    
    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        // TODO: show factory/mappings ?
        mappings.dump(out, indent);
    }
    
    /**
     * Get WebSocketServerFactory being used.
     *
     * @return the WebSocketServerFactory being used.
     */
    public WebSocketServerFactory getFactory()
    {
        return this.factory;
    }
    
    /**
     * Get the matching {@link MappedResource} for the provided target.
     *
     * @param target the target path
     * @return the matching resource, or null if no match.
     */
    public MappedResource<WebSocketCreator> getMatch(String target)
    {
        return this.mappings.getMatch(target);
    }
    
    /**
     * Used to configure the Default {@link WebSocketPolicy} used by all endpoints that
     * don't redeclare the values.
     *
     * @return the default policy for all WebSockets
     */
    public WebSocketPolicy getPolicy()
    {
        return this.factory.getPolicy();
    }
    
    /**
     * Manually add a WebSocket mapping.
     * <p>
     *     If mapping is added before this configuration is started, then it is persisted through
     *     stop/start of this configuration's lifecycle.  Otherwise it will be removed when
     *     this configuration is stopped.
     * </p>
     *
     * @param pathSpec the pathspec to respond on
     * @param creator the websocket creator to activate on the provided mapping.
     */
    public void addMapping(PathSpec pathSpec, WebSocketCreator creator)
    {
        WebSocketCreator wsCreator = creator;
        if (!isRunning())
        {
            wsCreator = new PersistedWebSocketCreator(creator);
        }
        mappings.put(pathSpec, wsCreator);
    }
    
    /**
     * Manually add a WebSocket mapping.
     *
     * @param spec the pathspec to respond on
     * @param creator the websocket creator to activate on the provided mapping
     * @deprecated use {@link #addMapping(PathSpec, Class)} instead.
     */
    @Deprecated
    public void addMapping(org.eclipse.jetty.websocket.server.pathmap.PathSpec spec, WebSocketCreator creator)
    {
        if (spec instanceof org.eclipse.jetty.websocket.server.pathmap.ServletPathSpec)
        {
            addMapping(new ServletPathSpec(spec.getSpec()), creator);
        }
        else if (spec instanceof org.eclipse.jetty.websocket.server.pathmap.RegexPathSpec)
        {
            addMapping(new RegexPathSpec(spec.getSpec()), creator);
        }
        else
        {
            throw new RuntimeException("Unsupported (Deprecated) PathSpec implementation type: " + spec.getClass().getName());
        }
    }
    
    /**
     * Manually add a WebSocket mapping.
     *
     * @param pathSpec the pathspec to respond on
     * @param endpointClass the endpoint class to use for new upgrade requests on the provided
     * pathspec (can be an {@link org.eclipse.jetty.websocket.api.annotations.WebSocket} annotated
     * POJO, or implementing {@link org.eclipse.jetty.websocket.api.WebSocketListener})
     */
    public void addMapping(PathSpec pathSpec, final Class<?> endpointClass)
    {
        mappings.put(pathSpec, new WebSocketCreator()
        {
            @Override
            public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
            {
                try
                {
                    return endpointClass.newInstance();
                }
                catch (InstantiationException | IllegalAccessException e)
                {
                    throw new WebSocketException("Unable to create instance of " + endpointClass.getName(), e);
                }
            }
        });
    }
    
    private class PersistedWebSocketCreator implements WebSocketCreator
    {
        private final WebSocketCreator delegate;
        
        public PersistedWebSocketCreator(WebSocketCreator delegate)
        {
            this.delegate = delegate;
        }
        
        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            return delegate.createWebSocket(req, resp);
        }
    
        @Override
        public String toString()
        {
            return "Persisted[" + super.toString() + "]";
        }
    }
}
