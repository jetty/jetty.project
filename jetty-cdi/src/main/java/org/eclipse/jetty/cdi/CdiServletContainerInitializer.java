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

import java.util.Set;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * A {@link ServletContainerInitializer} that introspects for a CDI API
 * implementation within a web application. If the CDI API is found, then
 * a {@link CdiDecorator} is registered as a {@link org.eclipse.jetty.util.Decorator}
 * for the context.
 */
public class CdiServletContainerInitializer implements ServletContainerInitializer
{
    private final static Logger LOG = Log.getLogger(CdiServletContainerInitializer.class);

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException
    {
        try
        {
            WebAppContext context = WebAppContext.getCurrentWebAppContext();
            context.getObjectFactory().addDecorator(new CdiDecorator(context));
            context.setAttribute("org.eclipse.jetty.cdi", "CdiDecorator");
            if (LOG.isDebugEnabled())
                LOG.debug("CdiDecorator enabled in " + ctx);
        }
        catch (UnsupportedOperationException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("CDI not found in " + ctx, e);
        }
        catch (Exception e)
        {
            LOG.warn("Incomplete CDI found in " + ctx, e);
        }
    }
}
