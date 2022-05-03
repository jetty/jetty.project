//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.server;

import java.io.IOException;
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
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.exception.WebSocketException;
import org.eclipse.jetty.websocket.core.server.internal.CreatorNegotiator;
import org.eclipse.jetty.websocket.core.server.internal.HandshakerSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
public class WebSocketMappings implements Dumpable, LifeCycle.Listener
{
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketMappings.class);
    public static final String WEBSOCKET_MAPPING_ATTRIBUTE = WebSocketMappings.class.getName();

    public static WebSocketMappings getMappings(ServletContext servletContext)
    {
        return (WebSocketMappings)servletContext.getAttribute(WEBSOCKET_MAPPING_ATTRIBUTE);
    }

    public static WebSocketMappings ensureMappings(ServletContext servletContext)
    {
        WebSocketMappings mapping = getMappings(servletContext);
        if (mapping == null)
        {
            mapping = new WebSocketMappings(WebSocketServerComponents.getWebSocketComponents(servletContext));
            servletContext.setAttribute(WEBSOCKET_MAPPING_ATTRIBUTE, mapping);
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

    private final PathMappings<WebSocketNegotiator> mappings = new PathMappings<>();
    private final WebSocketComponents components;
    private final Handshaker handshaker = new HandshakerSelector();

    public WebSocketMappings()
    {
        this(new WebSocketComponents());
    }

    public WebSocketMappings(WebSocketComponents components)
    {
        this.components = components;
    }

    public Handshaker getHandshaker()
    {
        return handshaker;
    }

    @Override
    public void lifeCycleStopping(LifeCycle context)
    {
        ContextHandler contextHandler = (ContextHandler)context;
        WebSocketMappings mapping = contextHandler.getBean(WebSocketMappings.class);
        if (mapping == this)
        {
            contextHandler.removeBean(mapping);
            mappings.reset();
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this, mappings);
    }

    public WebSocketNegotiator getWebSocketNegotiator(PathSpec pathSpec)
    {
        return mappings.get(pathSpec);
    }

    public WebSocketCreator getWebSocketCreator(PathSpec pathSpec)
    {
        WebSocketNegotiator negotiator = getWebSocketNegotiator(pathSpec);
        if (negotiator instanceof CreatorNegotiator)
            return  ((CreatorNegotiator)negotiator).getWebSocketCreator();
        return null;
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
     * @param factory the factory to use to create a FrameHandler for the websocket.
     * @param customizer the customizer to use to customize the WebSocket session.
     */
    public void addMapping(PathSpec pathSpec, WebSocketCreator creator, FrameHandlerFactory factory, Configuration.Customizer customizer) throws WebSocketException
    {
        mappings.put(pathSpec, WebSocketNegotiator.from(creator, factory, customizer));
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
     * @param negotiator the WebSocketNegotiator to use to create a FrameHandler for the websocket.
     */
    public void addMapping(PathSpec pathSpec, WebSocketNegotiator negotiator) throws WebSocketException
    {
        mappings.put(pathSpec, negotiator);
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
        MappedResource<WebSocketNegotiator> mapping = this.mappings.getMatch(target);
        if (mapping == null)
            return null;

        pathSpecConsumer.accept(mapping.getPathSpec());
        return mapping.getResource();
    }

    public boolean upgrade(HttpServletRequest request, HttpServletResponse response, Configuration.Customizer defaultCustomizer) throws IOException
    {
        // Since this may come from a filter, we need to be smart about determining the target path.
        // We should rely on the Servlet API for stripping URI path
        // parameters before attempting to match a specific mapping.
        String target = URIUtil.addPaths(request.getServletPath(), request.getPathInfo());

        WebSocketNegotiator negotiator = getMatchedNegotiator(target, pathSpec ->
        {
            // Store PathSpec resource mapping as request attribute,
            // for WebSocketCreator implementors to use later if they wish.
            request.setAttribute(PathSpec.class.getName(), pathSpec);
        });

        if (negotiator == null)
            return false;

        if (LOG.isDebugEnabled())
            LOG.debug("WebSocket Negotiated detected on {} for endpoint {}", target, negotiator);

        // We have an upgrade request
        return handshaker.upgradeRequest(negotiator, request, response, components, defaultCustomizer);
    }
}
