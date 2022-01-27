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

package org.eclipse.jetty.servlets;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConcatServletTest
{
    private Server server;
    private LocalConnector connector;

    @BeforeEach
    public void prepareServer()
    {
        server = new Server();
        connector = new LocalConnector(server);
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.RFC3986);
        server.addConnector(connector);
    }

    @AfterEach
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
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
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
        String request =
            "GET " + uri + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        String response = connector.getResponse(request);
        try (BufferedReader reader = new BufferedReader(new StringReader(response)))
        {
            while (true)
            {
                String line = reader.readLine();
                assertNotNull(line, "Line cannot be null");
                if (line.trim().isEmpty())
                    break;
            }
            assertEquals(resource1, reader.readLine());
            assertEquals(resource2, reader.readLine());
            assertNull(reader.readLine());
        }
    }

    @Test
    public void testDirectoryNotAccessible() throws Exception
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
        assertNotNull(context.getServletContext().getResource("/WEB-INF/one.js"));

        // Make sure ConcatServlet cannot see file system files.
        String uri = contextPath + concatPath + "?/trick/../../" + directoryFile.getName();
        String request =
            "GET " + uri + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        String response = connector.getResponse(request);
        assertTrue(response.startsWith("HTTP/1.1 404 "));
    }

    public static Stream<Arguments> webInfTestExamples()
    {
        return Stream.of(
            // Cannot access WEB-INF.
            Arguments.of("?/WEB-INF/", "HTTP/1.1 404 "),
            Arguments.of("?/WEB-INF/one.js", "HTTP/1.1 404 "),

            // Having a path segment and then ".." triggers a special case that the ConcatServlet must detect and avoid.
            Arguments.of("?/trick/../WEB-INF/one.js", "HTTP/1.1 404 "),

            // Make sure ConcatServlet behaves well if it's case insensitive.
            Arguments.of("?/trick/../web-inf/one.js", "HTTP/1.1 404 "),

            // Make sure ConcatServlet behaves well if encoded.
            Arguments.of("?/trick/..%2FWEB-INF%2Fone.js", "HTTP/1.1 404 "),
            Arguments.of("?/%2557EB-INF/one.js", "HTTP/1.1 500 "),
            Arguments.of("?/js/%252e%252e/WEB-INF/one.js", "HTTP/1.1 500 ")
        );
    }

    @ParameterizedTest
    @MethodSource("webInfTestExamples")
    public void testWEBINFResourceIsNotServed(String querystring, String expectedStatus) throws Exception
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
        assertNotNull(context.getServletContext().getResource("/WEB-INF/one.js"));

        String uri = contextPath + concatPath + querystring;
        String request =
            "GET " + uri + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        String response = connector.getResponse(request);
        assertThat(response, startsWith(expectedStatus));
    }
}
