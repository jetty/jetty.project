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

import java.time.Duration;

import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;

public interface WebSocketServletFactory extends FrameHandler.Configuration
{

    WebSocketExtensionRegistry getExtensionRegistry();

    @Override
    Duration getIdleTimeout();

    @Override
    void setIdleTimeout(Duration duration);

    @Override
    Duration getWriteTimeout();

    @Override
    void setWriteTimeout(Duration duration);

    @Override
    int getInputBufferSize();

    @Override
    void setInputBufferSize(int bufferSize);

    @Override
    long getMaxFrameSize();

    @Override
    void setMaxFrameSize(long maxFrameSize);

    @Override
    long getMaxBinaryMessageSize();

    @Override
    void setMaxBinaryMessageSize(long bufferSize);

    @Override
    long getMaxTextMessageSize();

    @Override
    void setMaxTextMessageSize(long bufferSize);

    @Override
    int getOutputBufferSize();

    @Override
    void setOutputBufferSize(int bufferSize);

    @Override
    boolean isAutoFragment();

    @Override
    void setAutoFragment(boolean autoFragment);

    void addMapping(String pathSpec, WebSocketCreator creator);

    /**
     * add a WebSocket mapping to a provided {@link WebSocketCreator}.
     * <p>
     * If mapping is added before this configuration is started, then it is persisted through
     * stop/start of this configuration's lifecycle.  Otherwise it will be removed when
     * this configuration is stopped.
     * </p>
     *
     * @param pathSpec the pathspec to respond on
     * @param creator the WebSocketCreator to use
     * @since 10.0
     */
    void addMapping(PathSpec pathSpec, WebSocketCreator creator);

    /**
     * Add a WebSocket mapping at PathSpec "/" for a creator which creates the endpointClass
     *
     * @param endpointClass the WebSocket class to use
     */
    void register(Class<?> endpointClass);

    /**
     * Add a WebSocket mapping at PathSpec "/" for a creator
     *
     * @param creator the WebSocketCreator to use
     */
    void setCreator(WebSocketCreator creator);

    /**
     * Returns the creator for the given path spec.
     *
     * @param pathSpec the pathspec to respond on
     * @return the websocket creator if path spec exists, or null
     */
    WebSocketCreator getMapping(PathSpec pathSpec);

    /**
     * Get the MappedResource for the given target path.
     *
     * @param target the target path
     * @return the MappedResource if matched, or null if not matched.
     */
    WebSocketCreator getMatch(String target);

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
    PathSpec parsePathSpec(String rawSpec);

    /**
     * Removes the mapping based on the given path spec.
     *
     * @param pathSpec the pathspec to respond on
     * @return true if underlying mapping were altered, false otherwise
     */
    boolean removeMapping(PathSpec pathSpec);
}
