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

package org.eclipse.jetty.ee10.webapp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.ee10.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class WebAppErrorPageHandlerTest
{
    public static class ErroringServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.sendError(403);
        }

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.sendError(403);
        }
    }

    @Test
    public void testErrorPageHandler() throws Exception
    {
        // create and configure Jetty server
        Server server = new Server(0);

        ContextHandlerCollection contexts = new ContextHandlerCollection();

        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        webapp.setBaseResourceAsPath(MavenTestingUtils.getTargetTestingPath());

        ErrorPageErrorHandler errorHandler = new ErrorPageErrorHandler();
        Path src = MavenTestingUtils.getTestResourcePathFile("forbidden.html");
        File srcFile = src.toFile();
        File destFile = MavenTestingUtils.getTargetTestingPath().resolve("forbidden.html").toFile();
        IO.copy(srcFile, destFile);

        errorHandler.addErrorPage(403, "/forbidden.html");
        webapp.setErrorHandler(errorHandler);

        webapp.getServletHandler().addServletWithMapping(ErroringServlet.class, "/demo/*");

        contexts.addHandler(webapp);

        Handler.Sequence handlers = new Handler.Sequence();
        handlers.addHandler(contexts);
        handlers.addHandler(new DefaultHandler());

        server.setHandler(handlers);
        
        server.start();
        int port = ((NetworkConnector)server.getConnectors()[0]).getLocalPort();
        try (HttpClient client = new HttpClient();)
        {
            client.start();

            String url = "http://localhost:" + port + "/demo/foo";

            // Check error dispatch on GET returns static error page
            ContentResponse response1 = client.GET(url);
            assertEquals(403, response1.getStatus());
            assertTrue(response1.getContentAsString().contains("FORBIDDEN"));
            // Check error dispatch on POST returns static error page
            Request post = client.POST(url);
            ContentResponse response2 = post.send();
            assertEquals(403, response2.getStatus());
            assertTrue(response2.getContentAsString().contains("FORBIDDEN"));

            // Check that a direct GET is unaffected
            url = "http://localhost:" + port + "/forbidden.html";
            response1 = client.GET(url);
            assertEquals(200, response1.getStatus());
            assertTrue(response1.getContentAsString().contains("FORBIDDEN"));
            // Check a direct POST has prior behaviour
            post = client.POST(url);
            response2 = post.send();
            assertEquals(405, response2.getStatus());
            assertTrue(response2.getContentAsString().contains("HTTP method POST is not supported by this URL"));
        }
    }
}
