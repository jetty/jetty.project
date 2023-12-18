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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.ResourceService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jetty.server.ResourceService.WelcomeMode.REDIRECT;
import static org.eclipse.jetty.server.ResourceService.WelcomeMode.REHANDLE;
import static org.eclipse.jetty.server.ResourceService.WelcomeMode.SERVE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ExtendWith(WorkDirExtension.class)
public class DefaultServletCombinationsTest
{
    public WorkDir workDir;

    public Path docRoot;
    private Server server;
    private LocalConnector connector;
    private ServletContextHandler context;

    @BeforeEach
    public void init() throws Exception
    {
        docRoot = workDir.getEmptyPathDir().resolve("docroot");
        FS.ensureDirExists(docRoot);
        Files.writeString(docRoot.resolve("index.html"), "Static index.html at root", UTF_8);
        Files.writeString(docRoot.resolve("foo.welcome"), "Static foo.welcome at root", UTF_8);

        Path subdirPath = docRoot.resolve("subdir");
        FS.ensureDirExists(subdirPath);
        Files.writeString(subdirPath.resolve("index.html"), "Static index.html at root subdir", UTF_8);
        Files.writeString(subdirPath.resolve("foo.welcome"), "Static foo.welcome at root subdir", UTF_8);

        Path emptyPath = docRoot.resolve("empty");
        FS.ensureDirExists(emptyPath);

        Path staticPath = docRoot.resolve("static");
        FS.ensureDirExists(staticPath);
        Files.writeString(staticPath.resolve("index.html"), "Static index.html at static", UTF_8);
        Files.writeString(staticPath.resolve("foo.welcome"), "Static foo.welcome at static", UTF_8);

        Path staticsubdirPath = staticPath.resolve("subdir");
        FS.ensureDirExists(staticsubdirPath);
        Files.writeString(staticsubdirPath.resolve("index.html"), "Static index.html at static subdir", UTF_8);
        Files.writeString(staticsubdirPath.resolve("foo.welcome"), "Static foo.welcome at static subdir", UTF_8);

        Path subdirHtmlPath = docRoot.resolve("subdirHtml");
        FS.ensureDirExists(subdirHtmlPath);
        Files.writeString(subdirHtmlPath.resolve("index.html"), "Static index.html at root subdirHtml", UTF_8);
        Path staticSubdirHtmlPath = staticPath.resolve("subdirHtml");
        FS.ensureDirExists(staticSubdirHtmlPath);
        Files.writeString(staticSubdirHtmlPath.resolve("index.html"), "Static index.html at static subdirHtml", UTF_8);

        Path subdirWelcomePath = docRoot.resolve("subdirWelcome");
        FS.ensureDirExists(subdirWelcomePath);
        Files.writeString(subdirWelcomePath.resolve("foo.welcome"), "Static foo.welcome at root subdirWelcome", UTF_8);

        Path staticSubdirWelcomePath = staticPath.resolve("subdirWelcome");
        FS.ensureDirExists(staticSubdirWelcomePath);
        Files.writeString(staticSubdirWelcomePath.resolve("foo.welcome"), "Static foo.welcome at static subdirWelcome", UTF_8);

        Path subdirEmptyPath = staticPath.resolve("empty");
        FS.ensureDirExists(subdirEmptyPath);

        server = new Server();

        connector = new LocalConnector(server);
        connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendServerVersion(false);

        context = new ServletContextHandler();
        context.setBaseResourceAsPath(docRoot);
        context.setContextPath("/ctx");
        context.setWelcomeFiles(new String[]{"index.html", "index.welcome"});

        ServletHolder welcomeExtHolder = context.addServlet(WelcomeServlet.class, "*.welcome");
        welcomeExtHolder.setInitParameter("mapping", "welcome extension");
    }

    private void startServer(boolean pathInfoOnly, ResourceService.WelcomeMode welcomeMode) throws Exception
    {
        ServletHolder defaultHolder;
        if (pathInfoOnly)
        {
            defaultHolder = context.addServlet(DefaultServlet.class, "/static/*");
            context.addServlet(TeapotServlet.class,  "/");
        }
        else
        {
            defaultHolder = context.addServlet(DefaultServlet.class, "/");
        }
        defaultHolder.setInitParameter("dirAllowed", "false");

        server.setHandler(context);
        server.addConnector(connector);

        server.start();
        // Must happen after start.
        ((DefaultServlet)defaultHolder.getServlet()).getResourceService().setWelcomeMode(welcomeMode);
    }

    @AfterEach
    public void destroy() throws Exception
    {
        LifeCycle.stop(server);
    }

    public static class WelcomeServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setStatus(HttpStatus.OK_200);
            String mapping = getInitParameter("mapping");
            resp.getWriter().print("Servlet at " + mapping);
        }
    }

    public static class TeapotServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setStatus(HttpStatus.IM_A_TEAPOT_418);
        }
    }

    record Data(boolean pathInfoOnly, ResourceService.WelcomeMode welcomeMode, String requestPath, int expectedStatus, String expected)
    {
    }

    public static Stream<Data> data()
    {
        List<Data> datas = new ArrayList<>();
        for (String requestPath : List.of("/", "/foo.welcome", "/subdirHtml/",
            "/subdirWelcome/", "/empty/", "/nothing/index.welcome", "/nothing/"))
        {
            for (ResourceService.WelcomeMode welcomeMode : List.of(SERVE, REDIRECT, REHANDLE))
            {
                for (boolean pathInfoOnly : List.of(false, true))
                {
                    int expectedStatus;
                    String expected;

                    switch (requestPath)
                    {
                        case "/" ->
                        {
                            switch (welcomeMode)
                            {
                                case SERVE ->
                                {
                                    expectedStatus = HttpStatus.OK_200;
                                    expected = "Static index.html at root";
                                }
                                case REDIRECT ->
                                {
                                    expectedStatus = HttpStatus.FOUND_302;
                                    expected = pathInfoOnly ? "/ctx/static/index.html" : "/ctx/index.html";
                                }
                                case REHANDLE ->
                                {
                                    expectedStatus = pathInfoOnly ? HttpStatus.IM_A_TEAPOT_418 : HttpStatus.NOT_FOUND_404;
                                    expected = null;
                                }
                                default -> throw new AssertionError();
                            }
                        }
                        case "/foo.welcome" ->
                        {
                            expectedStatus = HttpStatus.OK_200;
                            expected = pathInfoOnly ? "Static foo.welcome at root" : "Servlet at welcome extension";
                        }
                        case "/subdirHtml/" ->
                        {
                            switch (welcomeMode)
                            {
                                case SERVE ->
                                {
                                    expectedStatus = HttpStatus.OK_200;
                                    expected = "Static index.html at root subdirHtml";
                                }
                                case REDIRECT ->
                                {
                                    expectedStatus = HttpStatus.FOUND_302;
                                    expected = pathInfoOnly ? "/ctx/static/subdirHtml/index.html" : "/ctx/subdirHtml/index.html";
                                }
                                case REHANDLE ->
                                {
                                    expectedStatus = pathInfoOnly ? HttpStatus.IM_A_TEAPOT_418 : HttpStatus.NOT_FOUND_404;
                                    expected = null;
                                }
                                default -> throw new AssertionError();
                            }
                        }
                        case "/subdirWelcome/" ->
                        {
                            expectedStatus = HttpStatus.FORBIDDEN_403;
                            expected = null;
                        }
                        case "/empty/" ->
                        {
                            expectedStatus = HttpStatus.FORBIDDEN_403;
                            expected = null;
                        }
                        case "/nothing/index.welcome" ->
                        {
                            expectedStatus = pathInfoOnly ? HttpStatus.NOT_FOUND_404 : HttpStatus.OK_200;
                            expected = pathInfoOnly ? null : "Servlet at welcome extension";
                        }
                        case "/nothing/" ->
                        {
                            expectedStatus = HttpStatus.NOT_FOUND_404;
                            expected = null;
                        }
                        default -> throw new AssertionError();
                    }

                    datas.add(new Data(pathInfoOnly, welcomeMode, requestPath, expectedStatus, expected));
                }
            }
        }
        return datas.stream();
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testDefaultPathCombinations(Data data) throws Exception
    {
        startServer(data.pathInfoOnly(), data.welcomeMode());
        String requestPath = context.getContextPath() + (data.pathInfoOnly() ? "/static" : "") + data.requestPath();
        String rawResponse = connector.getResponse(String.format("""
            GET %s HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """, requestPath));
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        int status = response.getStatus();
        assertThat(response.toString(), status, is(data.expectedStatus()));
        if (status > 299 && status < 400)
            assertThat(response.get(HttpHeader.LOCATION), is(data.expected));
        else if (data.expected != null)
            assertThat(response.getContent(), is(data.expected));
    }
}
