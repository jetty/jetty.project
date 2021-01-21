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

package org.eclipse.jetty.annotations;

import java.util.List;

import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * ServletContainerInitializersStarter
 *
 * Call the onStartup() method on all ServletContainerInitializers, after having
 * found all applicable classes (if any) to pass in as args.
 */
public class ServletContainerInitializersStarter extends AbstractLifeCycle implements ServletContextHandler.ServletContainerInitializerCaller
{
    private static final Logger LOG = Log.getLogger(ServletContainerInitializersStarter.class);
    WebAppContext _context;

    public ServletContainerInitializersStarter(WebAppContext context)
    {
        _context = context;
    }

    /**
     * Call the doStart method of the ServletContainerInitializers
     *
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
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
                    LOG.debug("Calling ServletContainerInitializer " + i.getTarget().getClass().getName());
                i.callStartup(_context);
            }
            catch (Exception e)
            {
                LOG.warn(e);
                throw new RuntimeException(e);
            }
        }
    }
}
