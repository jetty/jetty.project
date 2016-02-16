//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.servlets;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ConcatServletTest
{
    private Server server;
    private LocalConnector connector;

    @Before
    public void prepareServer() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);
    }

    @After
    public void destroy() throws Exception
    {
        if (server != null)
            server.stop();
    }

    @Test
    public void testConcatenation() throws Exception
    {
        String contextPath = "";
        ServletContextHandler context = new ServletContextHandler(server, contextPath);
        server.setHandler(context);
        String concatPath = "/concat";
        context.addServlet(ConcatServlet.class, concatPath);
        ServletHolder resourceServletHolder = new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                String includedURI = (String)request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI);
                response.getOutputStream().println(includedURI);
            }
        });
        context.addServlet(resourceServletHolder, "/resource/*");
        server.start();

        String resource1 = "/resource/one.js";
        String resource2 = "/resource/two.js";
        String uri = contextPath + concatPath + "?" + resource1 + "&" + resource2;
        String request = "" +
                "GET " + uri + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        String response = connector.getResponses(request);
        try (BufferedReader reader = new BufferedReader(new StringReader(response)))
        {
            while (true)
            {
                String line = reader.readLine();
                if (line == null)
                    Assert.fail();
                if (line.trim().isEmpty())
                    break;
            }
            Assert.assertEquals(resource1, reader.readLine());
            Assert.assertEquals(resource2, reader.readLine());
            Assert.assertNull(reader.readLine());
        }
    }

    @Test
    public void testWEBINFResourceIsNotServed() throws Exception
    {
        File directoryFile = MavenTestingUtils.getTargetTestingDir();
        Path directoryPath = directoryFile.toPath();
        Path hiddenDirectory = directoryPath.resolve("WEB-INF");
        Files.createDirectories(hiddenDirectory);
        Path hiddenResource = hiddenDirectory.resolve("one.js");
        try (OutputStream output = Files.newOutputStream(hiddenResource))
        {
            output.write("function() {}".getBytes(StandardCharsets.UTF_8));
        }

        String contextPath = "";
        WebAppContext context = new WebAppContext(server, directoryPath.toString(), contextPath);
        server.setHandler(context);
        String concatPath = "/concat";
        context.addServlet(ConcatServlet.class, concatPath);
        server.start();

        // Verify that I can get the file programmatically, as required by the spec.
        Assert.assertNotNull(context.getServletContext().getResource("/WEB-INF/one.js"));

        // Having a path segment and then ".." triggers a special case
        // that the ConcatServlet must detect and avoid.
        String uri = contextPath + concatPath + "?/trick/../WEB-INF/one.js";
        String request = "" +
                "GET " + uri + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        String response = connector.getResponses(request);
        Assert.assertTrue(response.startsWith("HTTP/1.1 404 "));

        // Make sure ConcatServlet behaves well if it's case insensitive.
        uri = contextPath + concatPath + "?/trick/../web-inf/one.js";
        request = "" +
                "GET " + uri + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        response = connector.getResponses(request);
        Assert.assertTrue(response.startsWith("HTTP/1.1 404 "));

        // Make sure ConcatServlet behaves well if encoded.
        uri = contextPath + concatPath + "?/trick/..%2FWEB-INF%2Fone.js";
        request = "" +
                "GET " + uri + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        response = connector.getResponses(request);
        Assert.assertTrue(response.startsWith("HTTP/1.1 404 "));

        // Make sure ConcatServlet cannot see file system files.
        uri = contextPath + concatPath + "?/trick/../../" + directoryFile.getName();
        request = "" +
                "GET " + uri + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        response = connector.getResponses(request);
        Assert.assertTrue(response.startsWith("HTTP/1.1 404 "));
    }
}
