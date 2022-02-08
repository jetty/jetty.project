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

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.stream.Stream;
import javax.servlet.DispatcherType;

import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class WelcomeFilterTest
{
    private Server server;
    private LocalConnector connector;

    @BeforeEach
    public void prepareServer() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.RFC3986);
        server.addConnector(connector);

        Path directoryPath = MavenTestingUtils.getTargetTestingDir().toPath();
        Files.createDirectories(directoryPath);
        Path welcomeResource = directoryPath.resolve("welcome.html");
        try (OutputStream output = Files.newOutputStream(welcomeResource))
        {
            output.write("<h1>welcome page</h1>".getBytes(StandardCharsets.UTF_8));
        }

        Path otherResource = directoryPath.resolve("other.html");
        try (OutputStream output = Files.newOutputStream(otherResource))
        {
            output.write("<h1>other resource</h1>".getBytes(StandardCharsets.UTF_8));
        }

        Path hiddenDirectory = directoryPath.resolve("WEB-INF");
        Files.createDirectories(hiddenDirectory);
        Path hiddenResource = hiddenDirectory.resolve("one.js");
        try (OutputStream output = Files.newOutputStream(hiddenResource))
        {
            output.write("CONFIDENTIAL".getBytes(StandardCharsets.UTF_8));
        }

        Path hiddenWelcome = hiddenDirectory.resolve("index.html");
        try (OutputStream output = Files.newOutputStream(hiddenWelcome))
        {
            output.write("CONFIDENTIAL".getBytes(StandardCharsets.UTF_8));
        }

        WebAppContext context = new WebAppContext(server, directoryPath.toString(), "/");
        // Turn off memory-mapped behavior in DefaultServlet for Windows testing reasons.
        context.setInitParameter(DefaultServlet.CONTEXT_INIT + "useFileMappedBuffer", "false");
        server.setHandler(context);
        String concatPath = "/*";

        FilterHolder filterHolder = new FilterHolder(new WelcomeFilter());
        filterHolder.setInitParameter("welcome", "welcome.html");
        context.addFilter(filterHolder, concatPath, EnumSet.of(DispatcherType.REQUEST));
        server.start();

        // Verify that I can get the file programmatically, as required by the spec.
        assertNotNull(context.getServletContext().getResource("/WEB-INF/one.js"));
    }

    @AfterEach
    public void destroy() throws Exception
    {
        if (server != null)
            server.stop();
    }

    public static Stream<Arguments> argumentsStream()
    {
        return Stream.of(
            // Normal requests for the directory are redirected to the welcome page.
            Arguments.of("/", new String[]{"HTTP/1.1 200 ", "<h1>welcome page</h1>"}),

            // Try a normal resource (will bypass the filter).
            Arguments.of("/other.html", new String[]{"HTTP/1.1 200 ", "<h1>other resource</h1>"}),

            // Cannot access files in WEB-INF.
            Arguments.of("/WEB-INF/one.js", new String[]{"HTTP/1.1 404 "}),

            // Cannot serve welcome from WEB-INF.
            Arguments.of("/WEB-INF/", new String[]{"HTTP/1.1 404 "}),

            // Try to trick the filter into serving a protected resource.
            Arguments.of("/WEB-INF/one.js#/", new String[]{"HTTP/1.1 404 "}),
            Arguments.of("/js/../WEB-INF/one.js#/", new String[]{"HTTP/1.1 404 "}),

            // Test the URI is not double decoded in the dispatcher.
            Arguments.of("/%2557EB-INF/one.js%23/", new String[]{"HTTP/1.1 404 "})
        );
    }

    @ParameterizedTest
    @MethodSource("argumentsStream")
    public void testWelcomeFilter(String uri, String[] contains) throws Exception
    {
        String request =
            "GET " + uri + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        String response = connector.getResponse(request);
        for (String s : contains)
        {
            assertThat(response, containsString(s));
        }
    }
}
