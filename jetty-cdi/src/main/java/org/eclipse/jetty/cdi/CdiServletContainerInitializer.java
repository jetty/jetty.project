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

package org.eclipse.jetty.cdi;

import java.util.Objects;
import java.util.Set;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;

import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * A {@link ServletContainerInitializer} that introspects for a CDI API
 * implementation within a web application. If the CDI API is found, then
 * a {@link CdiDecorator} is registered as a {@link org.eclipse.jetty.util.Decorator}
 * for the context.
 * @see AnnotationConfiguration.ServletContainerInitializerOrdering
 */
public class CdiServletContainerInitializer implements ServletContainerInitializer
{
    public static final String CDI_INTEGRATION_ATTRIBUTE = "org.eclipse.jetty.cdi";
    private static final Logger LOG = Log.getLogger(CdiServletContainerInitializer.class);

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext ctx)
    {
        try
        {
            ServletContextHandler context = ServletContextHandler.getServletContextHandler(ctx);
            Objects.requireNonNull(context);
            context.getObjectFactory().addDecorator(new CdiDecorator(context));
            context.setAttribute(CDI_INTEGRATION_ATTRIBUTE, "CdiDecorator");
            LOG.info("CdiDecorator enabled in " + ctx);
        }
        catch (UnsupportedOperationException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("CDI not found in " + ctx, e);
        }
    }
}
