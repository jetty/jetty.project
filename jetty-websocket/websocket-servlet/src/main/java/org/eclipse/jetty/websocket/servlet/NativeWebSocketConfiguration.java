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

package org.eclipse.jetty.websocket.servlet;

import java.util.Iterator;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.RegexPathSpec;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.websocket.core.WebSocketException;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;

/**
 * Interface for Configuring WebSocket endpoints on a Jetty ServletContext
 * <p>
 * Only applicable if using {@link WebSocketUpgradeFilter}
 * </p>
 */
public class NativeWebSocketConfiguration extends ContainerLifeCycle implements MappedWebSocketServletNegotiator
{
    private final WebSocketServletFactory factory;
    private final PathMappings<WebSocketServletNegotiator> mappings = new PathMappings<>();

    public NativeWebSocketConfiguration(ServletContext context) throws ServletException
    {
        this(new WebSocketServletFactory(ServletContextWebSocketContainer.get(context)));
    }

    public NativeWebSocketConfiguration(WebSocketServletFactory webSocketServletFactory)
    {
        this.factory = webSocketServletFactory;
        addBean(this.factory);
        addBean(this.mappings);
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
     * @param negotiator the WebSocketServletNegotiator to use
     * @since 10.0
     */
    public void addMapping(PathSpec pathSpec, WebSocketServletNegotiator negotiator)
    {
        mappings.put(pathSpec, negotiator);
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
    public void addMapping(PathSpec pathSpec, WebSocketCreator creator)
    {
        WebSocketCreator wsCreator = creator;
        WebSocketServletNegotiator negotiator = new WebSocketServletNegotiator(factory, wsCreator);
        if (!isRunning())
        {
            negotiator = new PersistedWebSocketServletNegotiator(negotiator);
        }
        addMapping(pathSpec, negotiator);
    }

    /**
     * Manually add a WebSocket mapping.
     *
     * @param pathSpec the pathspec to respond on
     * @param endpointClass the endpoint class to use for new upgrade requests on the provided pathspec
     */
    public void addMapping(PathSpec pathSpec, final Class<?> endpointClass)
    {
        addMapping(pathSpec, (req, resp) ->
        {
            try
            {
                return endpointClass.newInstance();
            }
            catch (InstantiationException | IllegalAccessException e)
            {
                throw new WebSocketException("Unable to create instance of " + endpointClass.getName(), e);
            }
        });
    }

    public void addMapping(String rawSpec, WebSocketCreator creator)
    {
        addMapping(parsePathSpec(rawSpec), creator);
    }

    /**
     * Manually add a WebSocket mapping.
     *
     * @param rawSpec the pathspec to map to (see {@link #addMapping(String, WebSocketCreator)} for syntax details)
     * @param endpointClass the endpoint class to use for new upgrade requests on the provided pathspec
     */
    public void addMapping(String rawSpec, final Class<?> endpointClass)
    {
        addMapping(parsePathSpec(rawSpec), endpointClass);
    }

    @Override
    public void doStop() throws Exception
    {
        mappings.removeIf((mapped) -> !(mapped.getResource() instanceof PersistedWebSocketServletNegotiator));
        super.doStop();
    }

    /**
     * Get WebSocketServletFactory being used.
     *
     * @return the WebSocketServletFactory being used.
     */
    public WebSocketServletFactory getFactory()
    {
        return this.factory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WebSocketServletNegotiator getMapping(PathSpec pathSpec)
    {
        for (MappedResource<WebSocketServletNegotiator> mapping : mappings)
        {
            if (mapping.getPathSpec().equals(pathSpec))
            {
                if (mapping.getResource() instanceof PersistedWebSocketServletNegotiator)
                {
                    return ((PersistedWebSocketServletNegotiator) mapping.getResource()).delegate;
                }
                return mapping.getResource();
            }
        }
        return null;
    }

    public WebSocketServletNegotiator getMapping(String rawSpec)
    {
        return getMapping(parsePathSpec(rawSpec));
    }

    /**
     * Get the matching {@link MappedResource} for the provided target.
     *
     * @param target the target path
     * @return the matching resource, or null if no match.
     */
    @Override
    public MappedResource<WebSocketServletNegotiator> getMatch(String target)
    {
        MappedResource<WebSocketServletNegotiator> mapping = this.mappings.getMatch(target);
        if (mapping == null)
        {
            return null;
        }

        return mapping;
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
     * {@inheritDoc}
     */
    @Override
    public PathSpec parsePathSpec(String rawSpec)
    {
        // Determine what kind of path spec we are working with
        if (rawSpec.charAt(0) == '/' || rawSpec.startsWith("*.") || rawSpec.startsWith("servlet|"))
        {
            return new ServletPathSpec(rawSpec);
        }
        else if (rawSpec.charAt(0) == '^' || rawSpec.startsWith("regex|"))
        {
            return new RegexPathSpec(rawSpec);
        }
        else if (rawSpec.startsWith("uri-template|"))
        {
            return new UriTemplatePathSpec(rawSpec.substring("uri-template|".length()));
        }

        // TODO: add ability to load arbitrary jetty-http PathSpec implementation
        // TODO: perhaps via "fully.qualified.class.name|spec" style syntax

        throw new IllegalArgumentException("Unrecognized path spec syntax [" + rawSpec + "]");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeMapping(PathSpec pathSpec)
    {
        boolean removed = false;
        for (Iterator<MappedResource<WebSocketServletNegotiator>> iterator = mappings.iterator(); iterator.hasNext(); )
        {
            MappedResource<WebSocketServletNegotiator> mapping = iterator.next();
            if (mapping.getPathSpec().equals(pathSpec))
            {
                iterator.remove();
                removed = true;
            }
        }
        return removed;
    }

    public boolean removeMapping(String rawSpec)
    {
        return removeMapping(parsePathSpec(rawSpec));
    }

    private class PersistedWebSocketServletNegotiator extends WebSocketServletNegotiator
    {
        final WebSocketServletNegotiator delegate;

        public PersistedWebSocketServletNegotiator(WebSocketServletNegotiator negotiator)
        {
            super(negotiator.getFactory(), negotiator.getCreator(), negotiator.getFrameHandlerFactory());
            this.delegate = negotiator;
        }

        @Override
        public String toString()
        {
            return "Persisted[" + super.toString() + "]";
        }
    }
}
