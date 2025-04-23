//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.nio.file.Path;

import org.eclipse.jetty.util.resource.Resource;

/**
 * A {@link ContextHandler} that serves static content only.
 *
 * <p>
 * To set the directory to serve content from, set the base resource via the following methods.
 * </p>
 * <ul>
 *     <li>{@link #setBaseResource(Resource)}</li>
 *     <li>{@link #setBaseResourceAsPath(Path)}</li>
 *     <li>{@link #setBaseResourceAsString(String)}</li>
 * </ul>
 */
public class StaticContextHandler extends ContextHandler
{
    private final ResourceHandler resourceHandler;

    /**
     * Create a StaticContextHandler.
     */
    public StaticContextHandler()
    {
        this(null, null);
    }

    /**
     * Create a StaticContextHandler on a specific contextPath.
     *
     * @param contextPath the context path to serve static content from
     */
    public StaticContextHandler(String contextPath)
    {
        this(contextPath, null);
    }

    /**
     * Create a StaticContextHandler on a specific contextPath using a configured ResourceHandler.
     *
     * @param contextPath the context path
     * @param resourceHandler the resource handler
     */
    public StaticContextHandler(String contextPath, ResourceHandler resourceHandler)
    {
        // don't set contextPath if not provided, leave it at "default" of "/" (to maintain default-context-path behaviors)
        if (contextPath != null)
            setContextPath(contextPath);
        this.resourceHandler = resourceHandler != null ? resourceHandler : newResourceHandler();
        setHandler(this.resourceHandler);
    }

    /**
     * Override to customize a dynamically created ResourceHandler (such as from deploy).
     *
     * @return the customized ResourceHandler.
     */
    protected ResourceHandler newResourceHandler()
    {
        return new ResourceHandler();
    }

    public ResourceHandler getResourceHandler()
    {
        return resourceHandler;
    }
}
