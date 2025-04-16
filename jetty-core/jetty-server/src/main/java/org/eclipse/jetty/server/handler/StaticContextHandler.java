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
    public StaticContextHandler()
    {
        this("/");
    }

    public StaticContextHandler(String contextPath)
    {
        super();
        setContextPath(contextPath);
    }

    public StaticContextHandler(String contextPath, ResourceHandler resourceHandler)
    {
        super();
        setContextPath(contextPath);
        setHandler(resourceHandler);
    }

    @Override
    protected void initializeDefault(String keyName, Object value)
    {
        switch (keyName)
        {
            // The "Default Context Path" can be considered the calculated path from the deployer.
            // This is the name that exists before the XML is processed (and possibly changes the contextPath)
            case Deployable.DEFAULT_CONTEXT_PATH -> setContextPath((String)value);
        }
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
