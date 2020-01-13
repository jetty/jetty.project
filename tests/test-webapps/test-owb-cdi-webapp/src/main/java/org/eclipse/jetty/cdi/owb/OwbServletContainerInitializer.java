//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
