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

import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.RegexPathSpec;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFrameHandlerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static javax.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE;

/**
 */
public class WebSocketCreatorMapping implements Dumpable, FrameHandler.CoreCustomizer, WebSocketServletFactory
{
    private static final Logger LOG = Log.getLogger(WebSocketCreatorMapping.class);
    private final PathMappings<CreatorNegotiator> mappings = new PathMappings<>();
    private final Set<WebSocketServletFrameHandlerFactory> frameHandlerFactories = new HashSet<>();
    private Duration defaultIdleTimeout;
    private int defaultInputBufferSize;
    private long defaultMaxBinaryMessageSize = WebSocketConstants.DEFAULT_MAX_BINARY_MESSAGE_SIZE;
    private long defaultMaxTextMessageSize = WebSocketConstants.DEFAULT_MAX_TEXT_MESSAGE_SIZE;
    private long defaultMaxAllowedFrameSize = WebSocketConstants.DEFAULT_MAX_FRAME_SIZE;
    private int defaultOutputBufferSize = WebSocketConstants.DEFAULT_OUTPUT_BUFFER_SIZE;
    private boolean defaultAutoFragment = WebSocketConstants.DEFAULT_AUTO_FRAGMENT;
    private DecoratedObjectFactory objectFactory;
    private ClassLoader contextClassLoader;
    private WebSocketExtensionRegistry extensionRegistry;
    private ByteBufferPool bufferPool;

    public WebSocketCreatorMapping()
    {
        this(new WebSocketExtensionRegistry(), new DecoratedObjectFactory(), new MappedByteBufferPool());
    }

    public WebSocketCreatorMapping(WebSocketExtensionRegistry extensionRegistry, DecoratedObjectFactory objectFactory, ByteBufferPool bufferPool)
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
     * @param creator  the websocket creator to activate on the provided mapping.
     */
    public void addMapping(PathSpec pathSpec, WebSocketCreator creator)
    {
        // Handling for response forbidden (and similar paths)
        // no creation, sorry
        // No factory worked!
        mappings.put(pathSpec, new CreatorNegotiator(creator));
    }

    @Override
    public WebSocketCreator getMapping(PathSpec pathSpec)
    {
        CreatorNegotiator cn = mappings.get(pathSpec);
        return cn==null?null:cn.getWebSocketCreator();
    }

    @Override
    public WebSocketCreator getMatch(String target)
    {
        MappedResource<CreatorNegotiator> resource = mappings.getMatch(target);
        return resource==null?null:resource.getResource().getWebSocketCreator();
    }

    @Override
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
        Dumpable.dumpObjects(out,indent,this,mappings);
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

    public void addFrameHandlerFactory(WebSocketServletFrameHandlerFactory webSocketServletFrameHandlerFactory)
    {
        // TODO should this be done by a ServiceLoader?
        this.frameHandlerFactories.add(webSocketServletFrameHandlerFactory);
    }

    public void setDefaultIdleTimeout(Duration duration)
    {
        this.defaultIdleTimeout = duration;
    }

    public int getDefaultInputBufferSize()
    {
        return defaultInputBufferSize;
    }

    public void setDefaultInputBufferSize(int bufferSize)
    {
        this.defaultInputBufferSize = bufferSize;
    }

    public long getDefaultMaxAllowedFrameSize()
    {
        return this.defaultMaxAllowedFrameSize;
    }

    public void setDefaultMaxAllowedFrameSize(long maxFrameSize)
    {
        this.defaultMaxAllowedFrameSize = maxFrameSize;
    }

    public long getDefaultMaxBinaryMessageSize()
    {
        return defaultMaxBinaryMessageSize;
    }

    public void setDefaultMaxBinaryMessageSize(long bufferSize)
    {
        this.defaultMaxBinaryMessageSize = bufferSize;
    }

    public long getDefaultMaxTextMessageSize()
    {
        return defaultMaxTextMessageSize;
    }

    public void setDefaultMaxTextMessageSize(long bufferSize)
    {
        this.defaultMaxTextMessageSize = bufferSize;
    }

    public int getDefaultOutputBufferSize()
    {
        return this.defaultOutputBufferSize;
    }

    public void setDefaultOutputBufferSize(int bufferSize)
    {
        this.defaultOutputBufferSize = bufferSize;
    }

    public boolean isAutoFragment()
    {
        return this.defaultAutoFragment;
    }

    public void setAutoFragment(boolean autoFragment)
    {
        this.defaultAutoFragment = autoFragment;
    }

    /**
     * Get the matching {@link MappedResource} for the provided target.
     *
     * @param target the target path
     * @return the matching resource, or null if no match.
     */
    public WebSocketNegotiator getMatchedNegotiator(String target, Consumer<PathSpec> pathSpecConsumer)
    {
        MappedResource<CreatorNegotiator> mapping = this.mappings.getMatch(target);
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

    @Override
    public void customize(FrameHandler.CoreSession session)
    {
        session.setIdleTimeout(getDefaultIdleTimeout());
        session.setAutoFragment(isAutoFragment());
        session.setInputBufferSize(getDefaultInputBufferSize());
        session.setOutputBufferSize(getDefaultOutputBufferSize());
        session.setMaxFrameSize(getDefaultMaxAllowedFrameSize());
    }

    private class CreatorNegotiator extends WebSocketNegotiator.AbstractNegotiator
    {
        private final WebSocketCreator creator;

        public CreatorNegotiator(WebSocketCreator creator)
        {
            super(WebSocketCreatorMapping.this.getExtensionRegistry(), WebSocketCreatorMapping.this.getObjectFactory(),
                WebSocketCreatorMapping.this.getBufferPool(),
                WebSocketCreatorMapping.this);
            this.creator = creator;
        }

        public WebSocketCreator getWebSocketCreator()
        {
            return creator;
        }

        @Override
        public FrameHandler negotiate(Negotiation negotiation1)
        {
            return ((Function<Negotiation, FrameHandler>)negotiation ->
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

                    for (WebSocketServletFrameHandlerFactory factory : frameHandlerFactories)
                    {
                        FrameHandler frameHandler = factory.newFrameHandler(websocketPojo, upgradeRequest, upgradeResponse);
                        if (frameHandler != null)
                            return frameHandler;
                    }

                    if (frameHandlerFactories.isEmpty())
                        LOG.warn("There are no {} instances registered", WebSocketServletFrameHandlerFactory.class);

                    // No factory worked!
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
            }).apply(negotiation1);
        }
    }
}
