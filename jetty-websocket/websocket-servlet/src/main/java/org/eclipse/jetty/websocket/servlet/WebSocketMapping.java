//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.servlet;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.RegexPathSpec;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.exception.WebSocketException;
import org.eclipse.jetty.websocket.core.server.Handshaker;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static jakarta.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE;

/**
 * Mapping of pathSpec to a tupple of {@link WebSocketCreator}, {@link FrameHandlerFactory} and
 * {@link Configuration.Customizer}.
 * <p>
 * When the {@link #upgrade(HttpServletRequest, HttpServletResponse, Configuration.Customizer)}
 * method is called, a match for the pathSpec is looked for. If one is found then the
 * creator is used to create a POJO for the WebSocket endpoint, the factory is used to
 * wrap that POJO with a {@link FrameHandler} and the customizer is used to configure the resulting
 * {@link CoreSession}.</p>
 */
public class WebSocketMapping implements Dumpable, LifeCycle.Listener
{
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketMapping.class);

    public static WebSocketMapping getMapping(ServletContext servletContext, String mappingKey)
    {
        Object mappingObject = servletContext.getAttribute(mappingKey);

        if (mappingObject != null)
        {
            if (WebSocketMapping.class.isInstance(mappingObject))
                return (WebSocketMapping)mappingObject;
            else
                throw new IllegalStateException(
                    String.format("ContextHandler attribute %s is not of type WebSocketMapping: {%s}",
                        mappingKey, mappingObject.toString()));
        }

        return null;
    }

    public WebSocketCreator getMapping(PathSpec pathSpec)
    {
        Negotiator cn = mappings.get(pathSpec);
        return cn == null ? null : cn.getWebSocketCreator();
    }

    public static WebSocketMapping ensureMapping(ServletContext servletContext, String mappingKey)
    {
        WebSocketMapping mapping = getMapping(servletContext, mappingKey);

        if (mapping == null)
        {
            mapping = new WebSocketMapping(WebSocketComponents.ensureWebSocketComponents(servletContext));
            servletContext.setAttribute(mappingKey, mapping);
        }

        return mapping;
    }

    /**
     * Parse a PathSpec string into a PathSpec instance.
     * <p>
     * Recognized Path Spec syntaxes:
     * </p>
     * <dl>
     * <dt><code>/path/to</code> or <code>/</code> or <code>*.ext</code> or <code>servlet|{spec}</code></dt>
     * <dd>Servlet Syntax</dd>
     * <dt><code>^{spec}</code> or <code>regex|{spec}</code></dt>
     * <dd>Regex Syntax</dd>
     * <dt><code>uri-template|{spec}</code></dt>
     * <dd>URI Template (see JSR356 and RFC6570 level 1)</dd>
     * </dl>
     *
     * @param rawSpec the raw path spec as String to parse.
     * @return the {@link PathSpec} implementation for the rawSpec
     */
    public static PathSpec parsePathSpec(String rawSpec)
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

    public static final String DEFAULT_KEY = "org.eclipse.jetty.websocket.servlet.WebSocketMapping";

    private final PathMappings<Negotiator> mappings = new PathMappings<>();
    private final WebSocketComponents components;
    private final Handshaker handshaker = Handshaker.newInstance();

    public WebSocketMapping()
    {
        this(new WebSocketComponents());
    }

    public WebSocketMapping(WebSocketComponents components)
    {
        this.components = components;
    }

    @Override
    public void lifeCycleStopping(LifeCycle context)
    {
        ContextHandler contextHandler = (ContextHandler)context;
        WebSocketMapping mapping = contextHandler.getBean(WebSocketMapping.class);
        if (mapping == this)
        {
            contextHandler.removeBean(mapping);
            mappings.reset();
        }
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this, mappings);
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
     * @param factory the factory to use to create a FrameHandler for the websocket
     * @param customizer the customizer to use to customize the WebSocket session.
     */
    public void addMapping(PathSpec pathSpec, WebSocketCreator creator, FrameHandlerFactory factory, Configuration.Customizer customizer) throws WebSocketException
    {
        mappings.put(pathSpec, new Negotiator(creator, factory, customizer));
    }

    public boolean removeMapping(PathSpec pathSpec)
    {
        return mappings.remove(pathSpec);
    }

    /**
     * Get the matching {@link MappedResource} for the provided target.
     *
     * @param target the target path
     * @param pathSpecConsumer the path
     * @return the matching resource, or null if no match.
     */
    public WebSocketNegotiator getMatchedNegotiator(String target, Consumer<PathSpec> pathSpecConsumer)
    {
        MappedResource<Negotiator> mapping = this.mappings.getMatch(target);
        if (mapping == null)
            return null;

        pathSpecConsumer.accept(mapping.getPathSpec());
        return mapping.getResource();
    }

    public boolean upgrade(HttpServletRequest request, HttpServletResponse response, Configuration.Customizer defaultCustomizer) throws IOException
    {
        // Since this may be a filter, we need to be smart about determining the target path.
        // We should rely on the Container for stripping path parameters and its ilk before
        // attempting to match a specific mapped websocket creator.
        String target = request.getServletPath();
        if (request.getPathInfo() != null)
            target = target + request.getPathInfo();

        WebSocketNegotiator negotiator = getMatchedNegotiator(target, pathSpec ->
        {
            // Store PathSpec resource mapping as request attribute, for WebSocketCreator
            // implementors to use later if they wish
            request.setAttribute(PathSpec.class.getName(), pathSpec);
        });

        if (negotiator == null)
            return false;

        if (LOG.isDebugEnabled())
            LOG.debug("WebSocket Negotiated detected on {} for endpoint {}", target, negotiator);

        // We have an upgrade request
        return handshaker.upgradeRequest(negotiator, request, response, defaultCustomizer);
    }

    private class Negotiator extends WebSocketNegotiator.AbstractNegotiator
    {
        private final WebSocketCreator creator;
        private final FrameHandlerFactory factory;

        public Negotiator(WebSocketCreator creator, FrameHandlerFactory factory, Configuration.Customizer customizer)
        {
            super(components, customizer);
            this.creator = creator;
            this.factory = factory;
        }

        public WebSocketCreator getWebSocketCreator()
        {
            return creator;
        }

        @Override
        public FrameHandler negotiate(Negotiation negotiation) throws IOException
        {
            ServletContext servletContext = negotiation.getRequest().getServletContext();
            if (servletContext == null)
                throw new IllegalStateException("null servletContext from request");

            ServletUpgradeRequest upgradeRequest = new ServletUpgradeRequest(negotiation);
            ServletUpgradeResponse upgradeResponse = new ServletUpgradeResponse(negotiation);

            AtomicReference<Object> result = new AtomicReference<>();
            ((ContextHandler.Context)servletContext).getContextHandler().handle(() ->
                result.set(creator.createWebSocket(upgradeRequest, upgradeResponse)));
            Object websocketPojo = result.get();

            // Handling for response forbidden (and similar paths)
            if (upgradeResponse.isCommitted())
                return null;

            if (websocketPojo == null)
            {
                // no creation, sorry
                upgradeResponse.sendError(SC_SERVICE_UNAVAILABLE, "WebSocket Endpoint Creation Refused");
                return null;
            }

            return factory.newFrameHandler(websocketPojo, upgradeRequest, upgradeResponse);
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{%s,%s,%s}", getClass().getSimpleName(), hashCode(), creator, factory, getCustomizer());
        }
    }
}
