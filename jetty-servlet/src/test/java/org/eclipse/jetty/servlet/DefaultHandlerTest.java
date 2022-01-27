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

package org.eclipse.jetty.servlet;

import java.io.File;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
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
import static org.hamcrest.Matchers.is;
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

        server.setHandler(new HandlerList(contextA, contextFoo, new DefaultHandler()));
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

        String rawResponse = localConnector.getResponse(req.toString());

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));

        String body = response.getContent();
        assertThat(body, containsString("No context on this server matched or handled this request"));
        assertThat(body, containsString("Contexts known to this server are"));
        assertThat(body, containsString("<a href=\"/a/\">"));
        assertThat(body, containsString("<a href=\"/foo/\">"));
        assertThat(body, not(containsString(baseA.toString())));
        assertThat(body, not(containsString(baseFoo.toString())));
    }
}
