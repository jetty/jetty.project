//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.embedded;

import org.eclipse.jetty.rewrite.RewriteCustomizer;
import org.eclipse.jetty.rewrite.handler.CompactPathRule;
import org.eclipse.jetty.rewrite.handler.RewriteRegexRule;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;

public class RewriteServer
{
    public static void main( String[] args ) throws Exception
    {
        Server server = new Server(8080);
        
        HttpConfiguration config=server.getConnectors()[0].getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration();

        RewriteCustomizer rewrite = new RewriteCustomizer();
        config.addCustomizer(rewrite);
        rewrite.addRule(new CompactPathRule());
        rewrite.addRule(new RewriteRegexRule("(.*)foo(.*)","$1FOO$2"));
        
        ServletContextHandler context = new ServletContextHandler(
                ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);

        context.addServlet(DumpServlet.class, "/*");

        server.start();
        server.join();
    }
}
