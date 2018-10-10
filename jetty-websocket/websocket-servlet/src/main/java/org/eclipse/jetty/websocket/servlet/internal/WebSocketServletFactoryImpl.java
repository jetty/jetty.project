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

package org.eclipse.jetty.websocket.servlet.internal;

import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.RegexPathSpec;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketException;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFrameHandlerFactory;

/**
 * WebSocketServletFactory Implementation for working with WebSockets initiated from the Servlet API
 */
public class WebSocketServletFactoryImpl implements WebSocketServletFactory, WebSocketServletFrameHandlerFactory, Dumpable, FrameHandler.CoreCustomizer
{
    private static final Logger LOG = Log.getLogger(WebSocketServletFactoryImpl.class);
    private final PathMappings<WebSocketServletNegotiator> mappings = new PathMappings<>();
    private final Set<WebSocketServletFrameHandlerFactory> frameHandlerFactories = new HashSet<>();
    private Duration defaultIdleTimeout;
    private int defaultInputBufferSize;
    private long defaultMaxBinaryMessageSize = 65535;
    private long defaultMaxTextMessageSize = 65535;
    private long defaultMaxAllowedFrameSize = 65535;
    private int defaultOutputBufferSize = 4096;
    private boolean defaultAutoFragment = true;
    private DecoratedObjectFactory objectFactory;
    private ClassLoader contextClassLoader;
    private WebSocketExtensionRegistry extensionRegistry;
    private ByteBufferPool bufferPool;

    public WebSocketServletFactoryImpl()
    {
        this(new WebSocketExtensionRegistry(), new DecoratedObjectFactory(), new MappedByteBufferPool());
    }

    public WebSocketServletFactoryImpl(WebSocketExtensionRegistry extensionRegistry, DecoratedObjectFactory objectFactory, ByteBufferPool bufferPool)
    {
        this.extensionRegistry = extensionRegistry;
        this.objectFactory = objectFactory;
        this.bufferPool = bufferPool;
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
    @Override
    public void addMapping(PathSpec pathSpec, WebSocketCreator creator)
    {
        WebSocketCreator wsCreator = creator;
        WebSocketServletNegotiator negotiator = new WebSocketServletNegotiator(this, wsCreator, this);
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
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        mappings.dump(out, indent);
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

    @Override
    public Duration getDefaultIdleTimeout()
    {
        return defaultIdleTimeout;
    }

    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return this.extensionRegistry;
    }

    public DecoratedObjectFactory getObjectFactory()
    {
        return this.objectFactory;
    }

    @Override
    public void addFrameHandlerFactory(WebSocketServletFrameHandlerFactory frameHandlerFactory)
    {
        this.frameHandlerFactories.add(frameHandlerFactory);
    }

    @Override
    public FrameHandler newFrameHandler(Object websocketPojo, ServletUpgradeRequest upgradeRequest, ServletUpgradeResponse upgradeResponse)
    {
        if (frameHandlerFactories.isEmpty())
        {
            LOG.warn("There are no {} instances registered", WebSocketServletFrameHandlerFactory.class);
            return null;
        }

        for (WebSocketServletFrameHandlerFactory factory : frameHandlerFactories)
        {
            FrameHandler frameHandler = factory.newFrameHandler(websocketPojo, upgradeRequest, upgradeResponse);
            if (frameHandler != null)
                return frameHandler;
        }

        // No factory worked!
        return null;
    }

    @Override
    public void setDefaultIdleTimeout(Duration duration)
    {
        this.defaultIdleTimeout = duration;
    }

    @Override
    public int getDefaultInputBufferSize()
    {
        return defaultInputBufferSize;
    }

    @Override
    public void setDefaultInputBufferSize(int bufferSize)
    {
        this.defaultInputBufferSize = bufferSize;
    }

    @Override
    public long getDefaultMaxAllowedFrameSize()
    {
        return this.defaultMaxAllowedFrameSize;
    }

    @Override
    public void setDefaultMaxAllowedFrameSize(long maxFrameSize)
    {
        this.defaultMaxAllowedFrameSize = maxFrameSize;
    }

    @Override
    public long getDefaultMaxBinaryMessageSize()
    {
        return defaultMaxBinaryMessageSize;
    }

    @Override
    public void setDefaultMaxBinaryMessageSize(long bufferSize)
    {
        this.defaultMaxBinaryMessageSize = bufferSize;
    }

    @Override
    public long getDefaultMaxTextMessageSize()
    {
        return defaultMaxTextMessageSize;
    }

    @Override
    public void setDefaultMaxTextMessageSize(long bufferSize)
    {
        this.defaultMaxTextMessageSize = bufferSize;
    }

    @Override
    public int getDefaultOutputBufferSize()
    {
        return this.defaultOutputBufferSize;
    }

    @Override
    public void setDefaultOutputBufferSize(int bufferSize)
    {
        this.defaultOutputBufferSize = bufferSize;
    }

    @Override
    public boolean isAutoFragment()
    {
        return this.defaultAutoFragment;
    }

    @Override
    public void setAutoFragment(boolean autoFragment)
    {
        this.defaultAutoFragment = autoFragment;
    }

    @Override
    public WebSocketCreator getMapping(PathSpec pathSpec)
    {
        WebSocketServletNegotiator negotiator = getNegotiator(pathSpec);
        if(negotiator == null)
            return null;

        return negotiator.getCreator();
    }

    /**
     * {@inheritDoc}
     */
    public WebSocketServletNegotiator getNegotiator(PathSpec pathSpec)
    {
        for (MappedResource<WebSocketServletNegotiator> mapping : mappings)
        {
            if (mapping.getPathSpec().equals(pathSpec))
            {
                return mapping.getResource();
            }
        }
        return null;
    }

    public WebSocketServletNegotiator getNegotiator(String rawSpec)
    {
        return getNegotiator(parsePathSpec(rawSpec));
    }

    @Override
    public WebSocketCreator getMatch(String target)
    {
        MappedResource<WebSocketServletNegotiator> match = getMatchedResource(target);
        if(match == null || match.getResource() == null)
            return null;

        return match.getResource().getCreator();
    }

    /**
     * Get the matching {@link MappedResource} for the provided target.
     *
     * @param target the target path
     * @return the matching resource, or null if no match.
     */
    public MappedResource<WebSocketServletNegotiator> getMatchedResource(String target)
    {
        MappedResource<WebSocketServletNegotiator> mapping = this.mappings.getMatch(target);
        if (mapping == null)
        {
            return null;
        }

        return mapping;
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

    @Override
    public void customize(FrameHandler.CoreSession session)
    {
        session.setIdleTimeout(getDefaultIdleTimeout());
        session.setAutoFragment(isAutoFragment());
        session.setInputBufferSize(getDefaultInputBufferSize());
        session.setOutputBufferSize(getDefaultOutputBufferSize());
        session.setMaxFrameSize(getDefaultMaxAllowedFrameSize());
    }
}
