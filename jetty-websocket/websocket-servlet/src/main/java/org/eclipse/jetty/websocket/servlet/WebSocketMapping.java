//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.function.Consumer;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.RegexPathSpec;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.server.Handshaker;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;

import static javax.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE;

/**
 * Mapping of pathSpec to a tupple of {@link WebSocketCreator}, {@link FrameHandlerFactory} and
 * {@link org.eclipse.jetty.websocket.core.FrameHandler.Customizer}.
 * <p>
 * When the {@link #upgrade(HttpServletRequest, HttpServletResponse, FrameHandler.Customizer)}
 * method is called, a match for the pathSpec is looked for. If one is found then the
 * creator is used to create a POJO for the WebSocket endpoint, the factory is used to
 * wrap that POJO with a {@link FrameHandler} and the customizer is used to configure the resulting
 * {@link FrameHandler.CoreSession}.</p>
 */
public class WebSocketMapping implements Dumpable, LifeCycle.Listener
{
    private static final Logger LOG = Log.getLogger(WebSocketMapping.class);

    public static WebSocketMapping ensureMapping(ServletContext servletContext) throws ServletException
    {
        ContextHandler contextHandler = ContextHandler.getContextHandler(servletContext);

        // Ensure a mapping exists
        WebSocketMapping mapping = contextHandler.getBean(WebSocketMapping.class);
        if (mapping == null)
        {
            mapping = new WebSocketMapping();
            mapping.setContextClassLoader(servletContext.getClassLoader());
            contextHandler.addBean(mapping);
            contextHandler.addLifeCycleListener(mapping);
        }

        return mapping;
    }

    private final PathMappings<Negotiator> mappings = new PathMappings<>();
    private final Handshaker handshaker = Handshaker.newInstance();

    private DecoratedObjectFactory objectFactory;
    private ClassLoader contextClassLoader;
    private WebSocketExtensionRegistry extensionRegistry;
    private ByteBufferPool bufferPool;

    public WebSocketMapping()
    {
        this(new WebSocketExtensionRegistry(), new DecoratedObjectFactory(), new MappedByteBufferPool());
    }

    public WebSocketMapping(WebSocketExtensionRegistry extensionRegistry, DecoratedObjectFactory objectFactory, ByteBufferPool bufferPool)
    {
        this.extensionRegistry = extensionRegistry;
        this.objectFactory = objectFactory;
        this.bufferPool = bufferPool;
    }

    @Override
    public void lifeCycleStopping(LifeCycle context)
    {
        ContextHandler contextHandler = (ContextHandler) context;
        WebSocketMapping mapping = contextHandler.getBean(WebSocketMapping.class);
        if (mapping == this)
        {
            contextHandler.removeBean(mapping);
            mappings.reset();
        }
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
     * @param creator  the websocket creator to activate on the provided mapping.
     * @param factory  the factory to use to create a FrameHandler for the websocket
     * @param customizer the customizer to use to customize the WebSocket session.
     */
    public void addMapping(PathSpec pathSpec, WebSocketCreator creator, FrameHandlerFactory factory, FrameHandler.Customizer customizer)
    {
        // Handling for response forbidden (and similar paths)
        // no creation, sorry
        // No factory worked!
        mappings.put(pathSpec, new Negotiator(creator, factory, customizer));
    }

    public WebSocketCreator getMapping(PathSpec pathSpec)
    {
        Negotiator cn = mappings.get(pathSpec);
        return cn == null?null:cn.getWebSocketCreator();
    }

    public boolean removeMapping(PathSpec pathSpec)
    {
        return mappings.remove(pathSpec);
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

    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    public void setContextClassLoader(ClassLoader classLoader)
    {
        this.contextClassLoader = classLoader;
    }

    public ClassLoader getContextClassloader()
    {
        return contextClassLoader;
    }

    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return this.extensionRegistry;
    }

    public DecoratedObjectFactory getObjectFactory()
    {
        return this.objectFactory;
    }

    /**
     * Get the matching {@link MappedResource} for the provided target.
     *
     * @param target the target path
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

    public boolean upgrade(HttpServletRequest request, HttpServletResponse response, FrameHandler.Customizer defaultCustomizer)
    {
        try
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
        catch (Exception e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to upgrade: "+e);
            LOG.ignore(e);
        }
        return false;
    }

    private class Negotiator extends WebSocketNegotiator.AbstractNegotiator
    {
        private final WebSocketCreator creator;
        private final FrameHandlerFactory factory;

        public Negotiator(WebSocketCreator creator, FrameHandlerFactory factory, FrameHandler.Customizer customizer)
        {
            super(WebSocketMapping.this.getExtensionRegistry(), WebSocketMapping.this.getObjectFactory(),
                WebSocketMapping.this.getBufferPool(),
                customizer);
            this.creator = creator;
            this.factory = factory;
        }

        public WebSocketCreator getWebSocketCreator()
        {
            return creator;
        }


        @Override
        public FrameHandler negotiate(Negotiation negotiation)
        {
            ClassLoader old = Thread.currentThread().getContextClassLoader();
            try
            {
                Thread.currentThread().setContextClassLoader(getContextClassloader());

                ServletUpgradeRequest upgradeRequest = new ServletUpgradeRequest(negotiation);
                ServletUpgradeResponse upgradeResponse = new ServletUpgradeResponse(negotiation);

                Object websocketPojo = creator.createWebSocket(upgradeRequest, upgradeResponse);

                // Handling for response forbidden (and similar paths)
                if (upgradeResponse.isCommitted())
                    return null;

                if (websocketPojo == null)
                {
                    // no creation, sorry
                    upgradeResponse.sendError(SC_SERVICE_UNAVAILABLE, "WebSocket Endpoint Creation Refused");
                    return null;
                }

                FrameHandler frameHandler = factory.newFrameHandler(websocketPojo, upgradeRequest, upgradeResponse);
                if (frameHandler != null)
                    return frameHandler;

                return null;
            }
            catch (IOException e)
            {
                throw new RuntimeIOException(e);
            }
            catch (URISyntaxException e)
            {
                throw new RuntimeIOException("Unable to negotiate websocket due to mangled request URI", e);
            }
            finally
            {
                Thread.currentThread().setContextClassLoader(old);
            }
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{%s,%s,%s}",getClass().getSimpleName(), hashCode(), creator, factory, getCustomizer());
        }
    }
}
