//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.websocket.servlet.WebSocketCreator;

/**
 * Common interface for MappedWebSocketCreator
 */
public interface MappedWebSocketCreator
{
    /**
     * Add a mapping, of a pathspec to a WebSocketCreator.
     * <p>
     * Recognized Path Spec syntaxes
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
     * @param spec the path spec to use.
     * @param creator the websocket creator for this specific mapping
     */
    void addMapping(String spec, WebSocketCreator creator);

    /**
     * Add a mapping.
     *
     * @param spec the path spec to use
     * @param creator the creator for the mapping
     * @deprecated use {@link #addMapping(org.eclipse.jetty.http.pathmap.PathSpec, WebSocketCreator)} instead.
     * (support classes moved to generic jetty-http project)
     */
    @Deprecated
    void addMapping(org.eclipse.jetty.websocket.server.pathmap.PathSpec spec, WebSocketCreator creator);

    /**
     * Add a mapping.
     *
     * @param spec the path spec to use
     * @param creator the creator for the mapping
     * @since 9.2.20
     */
    void addMapping(org.eclipse.jetty.http.pathmap.PathSpec spec, WebSocketCreator creator);

    /**
     * /**
     * Returns the creator for the given path spec.
     *
     * @param spec the spec to test for (using the same spec syntax as seen in {@link #addMapping(String, WebSocketCreator)})
     * @return the websocket creator if path spec exists, or null
     */
    WebSocketCreator getMapping(String spec);

    /**
     * Removes the mapping based on the given path spec.
     *
     * @param spec the path spec to remove (using the same spec syntax as seen in {@link #addMapping(String, WebSocketCreator)})
     * @return true if underlying mapping were altered, false otherwise
     */
    boolean removeMapping(String spec);
}
