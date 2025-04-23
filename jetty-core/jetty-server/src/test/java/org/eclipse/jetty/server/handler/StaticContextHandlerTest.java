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

package org.eclipse.jetty.server.handler;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@ExtendWith(WorkDirExtension.class)
public class StaticContextHandlerTest
{
    public WorkDir workDir;
    private Server server;
    private LocalConnector localConnector;

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    private void startServer(Handler handler) throws Exception
    {
        server = new Server();
        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        server.setHandler(handler);
        server.start();
    }

    @Test
    public void testEmbeddedDefaultNoBase() throws Exception
    {
        StaticContextHandler staticContextHandler = new StaticContextHandler();
        staticContextHandler.setContextPath("/static");

        startServer(staticContextHandler);

        String rawResponse = localConnector.getResponse("""
            GET /static/ HTTP/1.1
            Host: local
            Connection: close
            
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(404));
    }

    @Test
    public void testEmbeddedDefaultWithContent() throws Exception
    {
        Path basedir = workDir.getEmptyPathDir();

        Files.writeString(basedir.resolve("test.txt"), "TEST TEXT");

        StaticContextHandler staticContextHandler = new StaticContextHandler();
        staticContextHandler.setContextPath("/static");
        staticContextHandler.setBaseResourceAsPath(basedir);

        startServer(staticContextHandler);

        String rawResponse = localConnector.getResponse("""
            GET /static/test.txt HTTP/1.1
            Host: local
            Connection: close
            
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("TEST TEXT"));

        // Directory listing (by default it is enabled)
        rawResponse = localConnector.getResponse("""
            GET /static/ HTTP/1.1
            Host: local
            Connection: close
            
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), allOf(
            containsString("Directory: /static/"),
            containsString("<table class=\"listing\">")
        ));
    }

    @Test
    public void testEmbeddedDefaultCustomResourceHandler() throws Exception
    {
        Path basedir = workDir.getEmptyPathDir();

        Files.writeString(basedir.resolve("test.txt"), "TEST TEXT");

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            ResourceHandler resourceHandler = new ResourceHandler();
            Resource resource = resourceFactory.newResource(basedir);
            resourceHandler.setBaseResource(resource);
            resourceHandler.setDirAllowed(false);

            StaticContextHandler staticContextHandler = new StaticContextHandler("/static", resourceHandler);
            startServer(staticContextHandler);

            String rawResponse = localConnector.getResponse("""
                GET /static/test.txt HTTP/1.1
                Host: local
                Connection: close
                
                """);
            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat(response.getStatus(), is(200));
            assertThat(response.getContent(), is("TEST TEXT"));

            // Directory listing (turned off in this configuration)
            rawResponse = localConnector.getResponse("""
                GET /static/ HTTP/1.1
                Host: local
                Connection: close
                
                """);
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.getStatus(), is(403));
            assertThat(response.getContent(), allOf(
                not(containsString("Directory: /static/")),
                not(containsString("<table class=\"listing\">"))
            ));
        }
    }
}
