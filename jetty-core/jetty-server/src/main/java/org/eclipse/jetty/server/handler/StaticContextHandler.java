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

import org.eclipse.jetty.server.Deployable;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.Resources;

/**
 * A ContextHandler that serves Static Content only.
 *
 * <p>
 * The Base Resource represents the static directory root.
 * </p>
 */
public class StaticContextHandler extends ContextHandler implements Deployable
{
    /**
     * Create a StaticContextHandler.
     */
    public StaticContextHandler()
    {
        super();
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
        super();
        setContextPath(contextPath);
        if (resourceHandler != null)
            setHandler(resourceHandler);
    }

    private boolean isResourceHandlerAlreadyPresent(Resource staticDir)
    {
        boolean alreadyExists = false;
        for (Handler handler : getHandlers())
        {
            if (handler instanceof ResourceHandler resourceHandler)
            {
                Resource baseResource = resourceHandler.getBaseResource();
                if (baseResource != null)
                {
                    URI baseResourceURI = baseResource.getURI();
                    if (baseResourceURI.equals(staticDir.getURI()))
                    {
                        alreadyExists = true;
                    }
                }
            }
        }
        return alreadyExists;
    }

    protected ResourceHandler newResourceHandler()
    {
        return new ResourceHandler();
    }

    @Override
    protected void doStart() throws Exception
    {
        Resource baseResource = getBaseResource();
        if (baseResource == null)
            throw new IllegalStateException("Base Resource is required.");

        if (!Resources.isDirectory(baseResource))
            throw new IllegalStateException("Base Resource is not a directory: " + baseResource);

        if (!isResourceHandlerAlreadyPresent(getBaseResource()))
            setHandler(newResourceHandler());

        super.doStart();
    }
}
