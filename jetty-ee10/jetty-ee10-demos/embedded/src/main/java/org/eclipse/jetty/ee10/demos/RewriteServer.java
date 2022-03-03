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

package org.eclipse.jetty.ee10.demos;

import java.util.Arrays;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.rewrite.RewriteCustomizer;
import org.eclipse.jetty.rewrite.handler.CompactPathRule;
import org.eclipse.jetty.rewrite.handler.RewriteRegexRule;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;

public class RewriteServer
{
    public static Server createServer(int port)
    {
        Server server = new Server(port);

        RewriteCustomizer rewrite = new RewriteCustomizer();
        rewrite.addRule(new CompactPathRule());
        rewrite.addRule(new RewriteRegexRule("(.*)foo(.*)", "$1FOO$2"));

        Arrays.stream(server.getConnectors())
            .forEach((connector) -> connector.getConnectionFactory(HttpConnectionFactory.class)
                .getHttpConfiguration().addCustomizer(rewrite));

        ServletContextHandler context = new ServletContextHandler(
            ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);

        context.addServlet(DumpServlet.class, "/*");

        return server;
    }

    public static void main(String[] args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        Server server = createServer(port);

        server.start();
        server.join();
    }
}
