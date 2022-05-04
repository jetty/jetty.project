//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Iterator;
import javax.servlet.ServletContext;

import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.MatchedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.RegexPathSpec;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
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
public class NativeWebSocketConfiguration extends ContainerLifeCycle implements MappedWebSocketCreator
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
        addBean(this.mappings);
    }

    @Override
    public void doStop() throws Exception
    {
        mappings.removeIf((mapped) -> !(mapped.getResource() instanceof PersistedWebSocketCreator));
        super.doStop();
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
        MatchedResource<WebSocketCreator> matched = this.mappings.getMatched(target);
        if (matched == null)
            return null;
        return matched.getMappedResource();
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
     * If mapping is added before this configuration is started, then it is persisted through
     * stop/start of this configuration's lifecycle.  Otherwise it will be removed when
     * this configuration is stopped.
     * </p>
     *
     * @param pathSpec the pathspec to respond on
     * @param creator the websocket creator to activate on the provided mapping.
     */
    @Override
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
     * @deprecated use {@link #addMapping(PathSpec, WebSocketCreator)} instead.
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
        mappings.put(pathSpec, (req, resp) ->
        {
            try
            {
                return endpointClass.getDeclaredConstructor().newInstance();
            }
            catch (Exception e)
            {
                throw new WebSocketException("Unable to create instance of " + endpointClass.getName(), e);
            }
        });
    }

    @Override
    public void addMapping(String rawspec, WebSocketCreator creator)
    {
        PathSpec spec = toPathSpec(rawspec);
        addMapping(spec, creator);
    }

    private PathSpec toPathSpec(String rawspec)
    {
        // Determine what kind of path spec we are working with
        if (rawspec.charAt(0) == '/' || rawspec.startsWith("*.") || rawspec.startsWith("servlet|"))
        {
            return new ServletPathSpec(rawspec);
        }
        else if (rawspec.charAt(0) == '^' || rawspec.startsWith("regex|"))
        {
            return new RegexPathSpec(rawspec);
        }
        else if (rawspec.startsWith("uri-template|"))
        {
            return new UriTemplatePathSpec(rawspec.substring("uri-template|".length()));
        }

        // TODO: add ability to load arbitrary jetty-http PathSpec implementation
        // TODO: perhaps via "fully.qualified.class.name|spec" style syntax

        throw new IllegalArgumentException("Unrecognized path spec syntax [" + rawspec + "]");
    }

    @Override
    public WebSocketCreator getMapping(String rawspec)
    {
        PathSpec pathSpec = toPathSpec(rawspec);
        for (MappedResource<WebSocketCreator> mapping : mappings)
        {
            if (mapping.getPathSpec().equals(pathSpec))
                return mapping.getResource();
        }
        return null;
    }

    @Override
    public boolean removeMapping(String rawspec)
    {
        PathSpec pathSpec = toPathSpec(rawspec);
        boolean removed = false;
        for (Iterator<MappedResource<WebSocketCreator>> iterator = mappings.iterator(); iterator.hasNext(); )
        {
            MappedResource<WebSocketCreator> mapping = iterator.next();
            if (mapping.getPathSpec().equals(pathSpec))
            {
                iterator.remove();
                removed = true;
            }
        }
        return removed;
    }

    /**
     * Manually add a WebSocket mapping.
     *
     * @param rawspec the pathspec to map to (see {@link MappedWebSocketCreator#addMapping(String, WebSocketCreator)} for syntax details)
     * @param endpointClass the endpoint class to use for new upgrade requests on the provided
     * pathspec (can be an {@link org.eclipse.jetty.websocket.api.annotations.WebSocket} annotated
     * POJO, or implementing {@link org.eclipse.jetty.websocket.api.WebSocketListener})
     */
    public void addMapping(String rawspec, final Class<?> endpointClass)
    {
        PathSpec pathSpec = toPathSpec(rawspec);
        addMapping(pathSpec, endpointClass);
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
