//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.websocket.server.pathmap.PathSpec;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;

/**
 * Common interface for MappedWebSocketCreator
 */
public interface MappedWebSocketCreator
{
    /**
     * Add a mapping.
     *
     * @param spec the path spec to use
     * @param creator the creator for the mapping
     * @deprecated use {@link #addMapping(org.eclipse.jetty.http.pathmap.PathSpec, WebSocketCreator)} instead.
     * (support classes moved to generic jetty-http project)
     */
    @Deprecated
    void addMapping(PathSpec spec, WebSocketCreator creator);
    
    /**
     * Add a mapping.
     *
     * @param spec the path spec to use
     * @param creator the creator for the mapping
     * @since 9.2.20
     */
    void addMapping(org.eclipse.jetty.http.pathmap.PathSpec spec, WebSocketCreator creator);
    
    /**
     * Get specific MappedResource for associated target.
     *
     * @param target the target to get mapping for
     * @return the MappedResource for the target, or null if no match.
     * @since 9.2.20
     */
    MappedResource<WebSocketCreator> getMapping(String target);
}
