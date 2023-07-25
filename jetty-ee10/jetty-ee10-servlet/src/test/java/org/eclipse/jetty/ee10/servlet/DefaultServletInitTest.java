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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

@ExtendWith(WorkDirExtension.class)
public class DefaultServletInitTest
{
    private Server server;
    private LocalConnector localConnector;

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    public void startServer(ServletContextHandler contextHandler) throws Exception
    {
        server = new Server();
        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);
        server.setHandler(contextHandler);
        server.start();
    }

    public static class ContextInit implements Consumer<ServletContextHandler>
    {
        private final String key;
        private final String value;

        public ContextInit(String key, String value)
        {
            this.key = key;
            this.value = value;
        }

        @Override
        public void accept(ServletContextHandler servletContextHandler)
        {
            servletContextHandler.setInitParameter(key, value);
        }

        @Override
        public String toString()
        {
            return "ContextInit[%s=%s]".formatted(key, value);
        }
    }

    public static class HolderInit implements Consumer<ServletHolder>
    {
        private final String key;
        private final String value;

        public HolderInit(String key, String value)
        {
            this.key = key;
            this.value = value;
        }

        @Override
        public void accept(ServletHolder servletHolder)
        {
            servletHolder.setInitParameter(key, value);
        }

        @Override
        public String toString()
        {
            return "HolderInit[%s=%s]".formatted(key, value);
        }
    }

    public record Config(ContextInit contextInit,
                         HolderInit holderInit)
    {}

    public static Stream<Config> welcomeServletsInitSource()
    {
        List<Config> configs = new ArrayList<>();

        configs.add(new Config(null, new HolderInit("welcomeServlets", "true")));
        configs.add(new Config(null, new HolderInit("welcomeServlets", "exact")));
        configs.add(new Config(new ContextInit("org.eclipse.jetty.servlet.Default.welcomeServlets", "true"), null));
        configs.add(new Config(new ContextInit("org.eclipse.jetty.servlet.Default.welcomeServlets", "exact"), null));

        return configs.stream();
    }

    @ParameterizedTest
    @MethodSource("welcomeServletsInitSource")
    public void testInitWelcomeServlets(Config config, WorkDir workDir) throws Exception
    {
        HttpServlet testServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setCharacterEncoding("utf-8");
                response.setContentType("text/plain");
                String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
                response.getWriter().println("Content from testServlet with pathInfo[" + pathInfo + "]");
            }
        };

        Path docroot = workDir.getEmptyPathDir();
        FS.ensureDirExists(docroot.resolve("foo"));

        ServletContextHandler contextHandler = new ServletContextHandler();
        ResourceFactory resourceFactory = ResourceFactory.of(contextHandler);
        contextHandler.setBaseResource(resourceFactory.newResource(docroot));
        contextHandler.setContextPath("/");
        contextHandler.setWelcomeFiles(new String[]{"testServlet"});

        ServletHolder myHolder = new ServletHolder("testServlet", testServlet);
        contextHandler.addServlet(myHolder, "/testServlet");

        ServletHolder defaultHolder = new ServletHolder("default", DefaultServlet.class);
        contextHandler.addServlet(defaultHolder, "/");

        if (config.contextInit != null)
            config.contextInit.accept(contextHandler);
        if (config.holderInit != null)
            config.holderInit.accept(defaultHolder);

        startServer(contextHandler);

        String rawRequest = """
            GET / HTTP/1.1
            Host: test
            Connection: close
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(localConnector.getResponse(rawRequest));
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), containsString("Content from testServlet with pathInfo[]"));
    }
}
