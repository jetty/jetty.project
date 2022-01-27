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

package org.eclipse.jetty.test;

import javax.inject.Inject;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

@WebListener
public class MyContextListener implements ServletContextListener
{
    @Inject
    public ServerID serverId;

    @Override
    public void contextInitialized(ServletContextEvent sce)
    {
        if (serverId == null)
            throw new IllegalStateException("CDI did not inject!");
        sce.getServletContext().setAttribute("ServerID", serverId.get());
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce)
    {
    }
}
