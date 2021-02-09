//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.servlet;

import java.io.File;

import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.PathResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

public class DefaultHandlerTest
{
    private Server server;
    private LocalConnector localConnector;
    private File baseA;
    private File baseFoo;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();

        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        File docRoot = MavenTestingUtils.getTargetTestingDir(DefaultHandlerTest.class.getName());
        FS.ensureDirExists(docRoot);

        baseA = new File(docRoot, "baseA");
        FS.ensureDirExists(baseA);

        baseFoo = new File(docRoot, "baseFoo");
        FS.ensureDirExists(baseFoo);

        ServletContextHandler contextA = new ServletContextHandler();
        contextA.setContextPath("/a");
        contextA.setBaseResource(new PathResource(baseA));

        ServletContextHandler contextFoo = new ServletContextHandler();
        contextFoo.setContextPath("/foo");
        contextFoo.setBaseResource(new PathResource(baseFoo));

        HandlerList handlers = new HandlerList();
        handlers.addHandler(contextA);
        handlers.addHandler(contextFoo);
        handlers.addHandler(new DefaultHandler());

        server.setHandler(handlers);
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testNotRevealBaseResource() throws Exception
    {
        StringBuilder req = new StringBuilder();
        req.append("GET / HTTP/1.0\r\n");
        req.append("\r\n");

        String rawResponse = localConnector.getResponses(req.toString());
        assertThat(rawResponse, containsString("404 Not Found"));
        assertThat(rawResponse, containsString("No context on this server matched or handled this request"));
        assertThat(rawResponse, containsString("Contexts known to this server are"));
        assertThat(rawResponse, containsString("<a href=\"/a/\">"));
        assertThat(rawResponse, containsString("<a href=\"/foo/\">"));
        assertThat(rawResponse, not(containsString(baseA.toString())));
        assertThat(rawResponse, not(containsString(baseFoo.toString())));
    }
}
