//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.cdi.owb;

import java.util.Set;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletException;

import org.apache.webbeans.servlet.WebBeansConfigurationListener;

/**
 * @deprecated This class will not be required once https://issues.apache.org/jira/browse/OWB-1296 is available
 */
@Deprecated
public class OwbServletContainerInitializer implements ServletContainerInitializer
{
    @Override
    public void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException
    {
        Listener listener = new Listener();
        listener.preInitialize(new ServletContextEvent(ctx));
        ctx.addListener(listener);
    }

    public static class Listener extends WebBeansConfigurationListener
    {
        void preInitialize(ServletContextEvent event)
        {
            super.contextInitialized(event);
        }

        @Override
        public void contextInitialized(ServletContextEvent event)
        {
        }
    }
}
