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

package com.acme.jaspi;

import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

/**
 * Registers the web application's own {@code ServerAuthModule} for its own {@code ServletContext},
 * which is how the Servlet Container Profile expects an application to supply one.
 */
public class RegisteringListener implements ServletContextListener
{
    @Override
    public void contextInitialized(ServletContextEvent sce)
    {
        AuthConfigFactory.getFactory().registerServerAuthModule(new HeaderAuthModule(), sce.getServletContext());
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce)
    {
        AuthConfigFactory.getFactory().removeServerAuthModule(sce.getServletContext());
    }
}
