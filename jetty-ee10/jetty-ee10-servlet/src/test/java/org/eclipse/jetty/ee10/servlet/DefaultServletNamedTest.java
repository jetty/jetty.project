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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

@ExtendWith(WorkDirExtension.class)
public class DefaultServletNamedTest
{
    private Server server;
    private LocalConnector localConnector;

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    private void startServer(ServletContextHandler contextHandler) throws Exception
    {
        server = new Server();

        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        server.setHandler(contextHandler);

        server.start();
    }

    public static Stream<Arguments> dispatchSource()
    {
        return Stream.of(
            Arguments.of(DispatcherType.FORWARD),
            Arguments.of(DispatcherType.INCLUDE)
        );
    }

    /**
     * Test of using Default servlet via a named dispatch.
     */
    @ParameterizedTest
    @MethodSource("dispatchSource")
    public void testDispatchToNamedDefault(DispatcherType dispatcherType, WorkDir workDir) throws Exception
    {
        Path docroot = workDir.getEmptyPathDir();

        Files.writeString(docroot.resolve("foo.txt"), "This is the foo.txt", StandardCharsets.UTF_8);

        ServletContextHandler contextHandler = new ServletContextHandler();
        ResourceFactory resourceFactory = ResourceFactory.of(contextHandler);
        contextHandler.setContextPath("/ctx");
        contextHandler.setBaseResource(resourceFactory.newResource(docroot));
        ServletHolder defaultHolder = new ServletHolder("default", DefaultServlet.class);
        contextHandler.addServlet(defaultHolder, "/");

        // This setup simulates a common REST setup where the controller is on `/*`
        // and is calling out to the "default" named-servlet to handle the request.

        HttpServlet testServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setHeader("X-TestServlet-PathInfo", request.getPathInfo());
                RequestDispatcher dispatcher = getServletContext().getNamedDispatcher("default");
                switch (dispatcherType)
                {
                    case FORWARD -> dispatcher.forward(request, response);
                    case INCLUDE -> dispatcher.include(request, response);
                    default -> throw new ServletException("Test doesn't support dispatcherType: " + dispatcherType);
                }
            }
        };

        // Setup a servlet on `/*`, so it handles all incoming requests.
        contextHandler.addServlet(testServlet, "/*");
        startServer(contextHandler);

        String rawRequest = """
            GET /ctx/foo.txt HTTP/1.1
            Host: test
            Connection: close
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(localConnector.getResponse(rawRequest));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));

        assertThat(response.get("X-TestServlet-PathInfo"), is("/foo.txt"));
        assertThat(response.getContent(), containsString("This is the foo.txt"));
    }
}
