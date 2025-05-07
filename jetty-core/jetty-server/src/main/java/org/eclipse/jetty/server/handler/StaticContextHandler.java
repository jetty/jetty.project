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

import java.net.URI;
import java.nio.file.Path;

import org.eclipse.jetty.server.Deployable;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;

/**
 * A {@link ContextHandler} that only serves static content from any source supported by {@link ResourceHandler}.
 *
 * <p>
 * To set the directory to serve content from, set the base resource via the following methods.
 * </p>
 * <ul>
 *     <li>{@link #setBaseResource(Resource)}</li>
 *     <li>{@link #setBaseResourceAsPath(Path)}</li>
 *     <li>{@link #setBaseResourceAsString(String)}</li>
 * </ul>
 *
 * <p>
 *     This is basically just a {@link ResourceHandler} with a context-path.
 * </p>
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

    @Override
    protected void initializeDefault(String keyName, Object value)
    {
        switch (keyName)
        {
            case Deployable.DIR_ALLOWED ->
            {
                if (value instanceof String str)
                    getResourceHandler().setDirAllowed(Boolean.parseBoolean(str));
                else if (value instanceof Boolean bool)
                    getResourceHandler().setDirAllowed(bool);
            }
        }
    }

    @Override
    public void setBaseResource(Resource baseResource)
    {
        if (baseResource == null)
        {
            super.setBaseResource(null);
        }
        else if (Resources.isDirectory(baseResource))
        {
            super.setBaseResource(baseResource);
        }
        else if (Resources.isReadableFile(baseResource))
        {
            // see if we need to make a passed archive into a zipfs archive.
            URI uri = baseResource.getURI();
            if (!"jar".equals(uri.getScheme()) && FileID.isArchive(uri))
            {
                Resource jarResource = ResourceFactory.of(this).newJarFileResource(uri);
                super.setBaseResource(jarResource);
            }
            else
            {
                super.setBaseResource(baseResource);
            }
        }
    }
}
