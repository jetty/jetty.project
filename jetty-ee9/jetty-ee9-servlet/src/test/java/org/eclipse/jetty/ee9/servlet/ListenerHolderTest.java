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

package org.eclipse.jetty.ee9.servlet;

import java.util.EventListener;

import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ListenerHolderTest
{
    public static class DummyListener implements EventListener
    {
    }

    @Test
    public void testCreateInstance() throws Exception
    {
        try (StacklessLogging ignore = new StacklessLogging(ServletHandler.class, ServletContextHandler.class))
        {
            //test without a ServletContextHandler or current ContextHandler
            ListenerHolder holder = new ListenerHolder();
            holder.setHeldClass(DummyListener.class);
            EventListener listener = holder.createInstance();
            assertNotNull(listener);

            //test with a ServletContextHandler
            Server server = new Server();
            ServletContextHandler context = new ServletContextHandler();
            server.setHandler(context);
            ServletHandler handler = context.getServletHandler();
            handler.addListener(holder);
            holder.setServletHandler(handler);
            server.start();
            assertNotNull(holder.getListener());
        }
    }
}
