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

package org.eclipse.jetty.websocket.server;

import java.time.Duration;
import java.util.Set;

import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

public class JettyWebSocketServletFactory implements WebSocketPolicy
{
    private WebSocketServletFactory factory;

    JettyWebSocketServletFactory(WebSocketServletFactory factory)
    {
        this.factory = factory;
    }

    public Set<String> getAvailableExtensionNames()
    {
        return factory.getExtensionRegistry().getAvailableExtensionNames();
    }

    @Override
    public WebSocketBehavior getBehavior()
    {
        return WebSocketBehavior.SERVER;
    }

    /**
     * If true, frames are automatically fragmented to respect the maximum frame size.
     *
     * @return whether to automatically fragment incoming WebSocket Frames.
     */
    public boolean isAutoFragment()
    {
        return factory.isAutoFragment();
    }

    /**
     * If set to true, frames are automatically fragmented to respect the maximum frame size.
     *
     * @param autoFragment whether to automatically fragment incoming WebSocket Frames.
     */
    public void setAutoFragment(boolean autoFragment)
    {
        factory.setAutoFragment(autoFragment);
    }

    /**
     * The maximum payload size of any WebSocket Frame which can be received.
     *
     * @return the maximum size of a WebSocket Frame.
     */
    public long getMaxFrameSize()
    {
        return factory.getMaxFrameSize();
    }

    /**
     * The maximum payload size of any WebSocket Frame which can be received.
     * <p>
     * WebSocket Frames over this maximum will result in a close code 1009 {@link StatusCode#MESSAGE_TOO_LARGE}
     * </p>
     *
     * @param maxFrameSize the maximum allowed size of a WebSocket Frame.
     */
    public void setMaxFrameSize(long maxFrameSize)
    {
        factory.setMaxFrameSize(maxFrameSize);
    }

    @Override
    public Duration getIdleTimeout()
    {
        return factory.getIdleTimeout();
    }

    @Override
    public void setIdleTimeout(Duration duration)
    {
        factory.setIdleTimeout(duration);
    }

    @Override
    public int getInputBufferSize()
    {
        return factory.getInputBufferSize();
    }

    @Override
    public void setInputBufferSize(int bufferSize)
    {
        factory.setInputBufferSize(bufferSize);
    }

    @Override
    public long getMaxBinaryMessageSize()
    {
        return factory.getMaxBinaryMessageSize();
    }

    @Override
    public void setMaxBinaryMessageSize(long bufferSize)
    {
        factory.setMaxBinaryMessageSize(bufferSize);
    }

    @Override
    public long getMaxTextMessageSize()
    {
        return factory.getMaxTextMessageSize();
    }

    @Override
    public void setMaxTextMessageSize(long bufferSize)
    {
        factory.setMaxTextMessageSize(bufferSize);
    }

    @Override
    public int getOutputBufferSize()
    {
        return factory.getOutputBufferSize();
    }

    @Override
    public void setOutputBufferSize(int bufferSize)
    {
        factory.setOutputBufferSize(bufferSize);
    }


    /**
     * add a WebSocket mapping to a provided {@link JettyWebSocketCreator}.
     * <p>
     * If mapping is added before this configuration is started, then it is persisted through
     * stop/start of this configuration's lifecycle.  Otherwise it will be removed when
     * this configuration is stopped.
     * </p>
     *
     * @param pathSpec the pathspec to respond on
     * @param creator  the WebSocketCreator to use
     * @since 10.0
     */
    public void addMapping(String pathSpec, JettyWebSocketCreator creator)
    {
        factory.addMapping(pathSpec, new WrappedCreator(creator));
    }

    /**
     * add a WebSocket mapping to a provided {@link JettyWebSocketCreator}.
     * <p>
     * If mapping is added before this configuration is started, then it is persisted through
     * stop/start of this configuration's lifecycle.  Otherwise it will be removed when
     * this configuration is stopped.
     * </p>
     *
     * @param pathSpec the pathspec to respond on
     * @param creator  the WebSocketCreator to use
     * @since 10.0
     */
    public void addMapping(PathSpec pathSpec, JettyWebSocketCreator creator)
    {
        factory.addMapping(pathSpec, new WrappedCreator(creator));
    }

    /**
     * Add a WebSocket mapping at PathSpec "/" for a creator which creates the endpointClass
     *
     * @param endpointClass the WebSocket class to use
     */
    public void register(Class<?> endpointClass)
    {
        factory.register(endpointClass);
    }

    /**
     * Add a WebSocket mapping at PathSpec "/" for a creator
     *
     * @param creator the WebSocketCreator to use
     */
    public void setCreator(JettyWebSocketCreator creator)
    {
        factory.setCreator(new WrappedCreator(creator));
    }

    /**
     * Returns the creator for the given path spec.
     *
     * @param pathSpec the pathspec to respond on
     * @return the websocket creator if path spec exists, or null
     */
    public JettyWebSocketCreator getMapping(PathSpec pathSpec)
    {
        WebSocketCreator creator = factory.getMapping(pathSpec);
        if (creator instanceof WrappedCreator)
            return ((WrappedCreator)creator).getCreator();

        return null;
    }

    /**
     * Get the MappedResource for the given target path.
     *
     * @param target the target path
     * @return the MappedResource if matched, or null if not matched.
     */
    public JettyWebSocketCreator getMatch(String target)
    {
        WebSocketCreator creator = factory.getMatch(target);
        if (creator instanceof WrappedCreator)
            return ((WrappedCreator)creator).getCreator();

        return null;
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
        return factory.parsePathSpec(rawSpec);
    }

    /**
     * Removes the mapping based on the given path spec.
     *
     * @param pathSpec the pathspec to respond on
     * @return true if underlying mapping were altered, false otherwise
     */
    public boolean removeMapping(PathSpec pathSpec)
    {
        return factory.removeMapping(pathSpec);
    }


    private static class WrappedCreator implements WebSocketCreator
    {
        private JettyWebSocketCreator creator;

        private WrappedCreator(JettyWebSocketCreator creator)
        {
            this.creator = creator;
        }

        public JettyWebSocketCreator getCreator()
        {
            return creator;
        }

        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            return creator.createWebSocket(new JettyServerUpgradeRequest(req), new JettyServerUpgradeResponse(resp));
        }
    }
}
