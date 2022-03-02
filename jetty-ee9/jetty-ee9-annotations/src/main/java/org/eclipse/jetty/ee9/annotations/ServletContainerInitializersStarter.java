//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.annotations;

import java.util.List;

import org.eclipse.jetty.ee9.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ServletContainerInitializersStarter
 *
 * Call the onStartup() method on all ServletContainerInitializers, after having
 * found all applicable classes (if any) to pass in as args.
 * @deprecated
 */
@Deprecated
public class ServletContainerInitializersStarter extends AbstractLifeCycle implements ServletContextHandler.ServletContainerInitializerCaller
{
    private static final Logger LOG = LoggerFactory.getLogger(ServletContainerInitializersStarter.class);
    WebAppContext _context;

    public ServletContainerInitializersStarter(WebAppContext context)
    {
        _context = context;
    }

    @Override
    public void doStart()
    {
        List<ContainerInitializer> initializers = (List<ContainerInitializer>)_context.getAttribute(AnnotationConfiguration.CONTAINER_INITIALIZERS);
        if (initializers == null)
            return;

        for (ContainerInitializer i : initializers)
        {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Calling ServletContainerInitializer {}", i.getTarget().getClass().getName());
                i.callStartup(_context);
            }
            catch (Exception e)
            {
                LOG.warn("Failed to call startup on {}", i, e);
                throw new RuntimeException(e);
            }
        }
    }
}
