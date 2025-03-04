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

package org.eclipse.jetty.demo.servlet6;

import java.io.IOException;
import java.nio.file.Path;

import org.eclipse.jetty.ee11.webapp.Configurations;
import org.eclipse.jetty.ee11.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;

public class SimpleWebAppServer
{
    public static Server createServer(int port) throws IOException
    {
        Server server = new Server(port);

        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        JettyDemos.MavenCoordinate mavenCoordinate = new JettyDemos.MavenCoordinate("org.eclipse.jetty.demos",
                "jetty-servlet6-demo-simple-webapp", "", "war");
        Path warFile = JettyDemos.find("target/jetty-servlet6-demo-simple-webapp-@VER@", mavenCoordinate);
        webapp.setWar(warFile.toString());

        server.setHandler(webapp);
        return server;
    }

    public static void main(String[] args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        Server server = createServer(port);

        Configurations.setServerDefault(server);

        server.start();

        server.dumpStdErr();

        server.join();
    }
}
