// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.embedded;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;

/* ------------------------------------------------------------ */
/**
 * A {@link ContextHandler} provides a common environment for multiple Handlers,
 * such as: URI context path, class loader, static resource base.
 * 
 * Typically a ContextHandler is used only when multiple contexts are likely.
 */
public class OneContext
{
    public static void main(String[] args) throws Exception
    {
        Server server = new Server(8080);

        ContextHandler context = new ContextHandler();
        context.setContextPath("/");
        context.setResourceBase(".");
        context.setClassLoader(Thread.currentThread().getContextClassLoader());
        server.setHandler(context);

        context.setHandler(new HelloHandler());

        server.start();
        server.join();
    }
}
