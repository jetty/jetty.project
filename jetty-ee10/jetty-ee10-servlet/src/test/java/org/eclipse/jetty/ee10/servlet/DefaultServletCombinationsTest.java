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

        Path subdirEmptyPath = staticPath.resolve("subdirEmpty");
        FS.ensureDirExists(subdirEmptyPath);

        server = new Server();

        connector = new LocalConnector(server);
        connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendServerVersion(false);

        context = new ServletContextHandler();
        context.setBaseResourceAsPath(docRoot);
        context.setContextPath("/");
        context.setWelcomeFiles(new String[]{"index.html", "index.welcome"});

        ServletHolder welcomeExtHolder = context.addServlet(WelcomeServlet.class, "*.welcome");
        welcomeExtHolder.setInitParameter("mapping", "welcome extension");
    }

    private void startServer(boolean pathInfoOnly, ResourceService.WelcomeMode welcomeMode) throws Exception
    {
        ServletHolder defaultHolder = context.addServlet(DefaultServlet.class, "/static/*");
        defaultHolder.setInitParameter("pathInfoOnly", String.valueOf(pathInfoOnly));
        defaultHolder.setInitParameter("dirAllowed", "false");

        server.setHandler(context);
        server.addConnector(connector);

        server.start();
        ((DefaultServlet)defaultHolder.getServlet()).getResourceService().setWelcomeMode(welcomeMode);
    }

    @AfterEach
    public void destroy() throws Exception
    {
        server.stop();
        server.join();
    }

    public static class WelcomeServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setStatus(200);
            String mapping = getInitParameter("mapping");
            resp.getWriter().print("Servlet at " + mapping);
        }
    }

    record Data(boolean pathInfoOnly, ResourceService.WelcomeMode welcomeMode, String requestPath, int expectedStatus, String expected)
    {
    }

    public static Stream<Data> data()
    {
        return Stream.of(
            new Data(false, SERVE, "/foo.welcome", HttpStatus.OK_200, "Servlet at welcome extension"),
            new Data(false, REDIRECT, "/foo.welcome", HttpStatus.OK_200, "Servlet at welcome extension"),
            new Data(false, REHANDLE, "/foo.welcome", HttpStatus.OK_200, "Servlet at welcome extension"),
            new Data(true, SERVE, "/foo.welcome", HttpStatus.OK_200, "Servlet at welcome extension"),
            new Data(true, REDIRECT, "/foo.welcome", HttpStatus.OK_200, "Servlet at welcome extension"),
            new Data(true, REHANDLE, "/foo.welcome", HttpStatus.OK_200, "Servlet at welcome extension"),

            new Data(false, SERVE, "/static/foo.welcome", HttpStatus.OK_200, "Static foo.welcome at static"),
            new Data(false, REDIRECT, "/static/foo.welcome", HttpStatus.OK_200, "Static foo.welcome at static"),
            new Data(false, REHANDLE, "/static/foo.welcome", HttpStatus.OK_200, "Static foo.welcome at static"),
            new Data(true, SERVE, "/static/foo.welcome", HttpStatus.OK_200, "Static foo.welcome at root"),
            new Data(true, REDIRECT, "/static/foo.welcome", HttpStatus.OK_200, "Static foo.welcome at root"),
            new Data(true, REHANDLE, "/static/foo.welcome", HttpStatus.OK_200, "Static foo.welcome at root"),

            new Data(false, SERVE, "/static/", HttpStatus.OK_200, "Static index.html at static"),
            new Data(false, REDIRECT, "/static/", HttpStatus.FOUND_302, "http://local/static/index.html"),
            new Data(false, REHANDLE, "/static/", HttpStatus.OK_200, "Static index.html at static"),
            new Data(true, SERVE, "/static/", HttpStatus.OK_200, "Static index.html at root"),
            new Data(true, REDIRECT, "/static/", HttpStatus.FOUND_302, "http://local/static/static/index.html"),
            new Data(true, REHANDLE, "/static/", HttpStatus.OK_200, "Static index.html at root"),

            new Data(false, SERVE, "/static/subdir/", HttpStatus.OK_200, "Static index.html at static subdir"),
            new Data(false, REDIRECT, "/static/subdir/", HttpStatus.FOUND_302, "http://local/static/subdir/index.html"),
            new Data(false, REHANDLE, "/static/subdir/", HttpStatus.OK_200, "Static index.html at static subdir"),
            new Data(true, SERVE, "/static/subdir/", HttpStatus.OK_200, "Static index.html at root subdir"),
            new Data(true, REDIRECT, "/static/subdir/", HttpStatus.FOUND_302, "http://local/static/static/subdir/index.html"),
            new Data(true, REHANDLE, "/static/subdir/", HttpStatus.OK_200, "Static index.html at root subdir"),

            new Data(false, SERVE, "/static/subdirHtml/", HttpStatus.OK_200, "Static index.html at static subdirHtml"),
            new Data(false, REDIRECT, "/static/subdirHtml/", HttpStatus.FOUND_302, "http://local/static/subdirHtml/index.html"),
            new Data(false, REHANDLE, "/static/subdirHtml/", HttpStatus.OK_200, "Static index.html at static subdirHtml"),
            new Data(true, SERVE, "/static/subdirHtml/", HttpStatus.OK_200, "Static index.html at root subdirHtml"),
            new Data(true, REDIRECT, "/static/subdirHtml/", HttpStatus.FOUND_302, "http://local/static/static/subdirHtml/index.html"),
            new Data(true, REHANDLE, "/static/subdirHtml/", HttpStatus.OK_200, "Static index.html at root subdirHtml"),

            new Data(false, SERVE, "/static/subdirWelcome/", HttpStatus.FORBIDDEN_403, null),
            new Data(false, REDIRECT, "/static/subdirWelcome/", HttpStatus.FORBIDDEN_403, null),
            new Data(false, REHANDLE, "/static/subdirWelcome/", HttpStatus.FORBIDDEN_403, null),
            new Data(true, SERVE, "/static/subdirWelcome/", HttpStatus.FORBIDDEN_403, null),
            new Data(true, REDIRECT, "/static/subdirWelcome/", HttpStatus.FORBIDDEN_403, null),
            new Data(true, REHANDLE, "/static/subdirWelcome/", HttpStatus.FORBIDDEN_403, null),

            new Data(false, SERVE, "/static/subdirEmpty/", HttpStatus.FORBIDDEN_403, null),
            new Data(false, REDIRECT, "/static/subdirEmpty/", HttpStatus.FORBIDDEN_403, null),
            new Data(false, REHANDLE, "/static/subdirEmpty/", HttpStatus.FORBIDDEN_403, null),
            new Data(true, SERVE, "/static/subdirEmpty/", HttpStatus.NOT_FOUND_404, null),
            new Data(true, REDIRECT, "/static/subdirEmpty/", HttpStatus.NOT_FOUND_404, null),
            new Data(true, REHANDLE, "/static/subdirEmpty/", HttpStatus.NOT_FOUND_404, null),

            new Data(false, SERVE, "/empty/", HttpStatus.NOT_FOUND_404, null),
            new Data(false, REDIRECT, "/empty/", HttpStatus.NOT_FOUND_404, null),
            new Data(false, REHANDLE, "/empty/", HttpStatus.NOT_FOUND_404, null),
            new Data(true, SERVE, "/empty/", HttpStatus.NOT_FOUND_404, null),
            new Data(true, REDIRECT, "/empty/", HttpStatus.NOT_FOUND_404, null),
            new Data(true, REHANDLE, "/empty/", HttpStatus.NOT_FOUND_404, null),

            new Data(false, SERVE, "/nothing/index.welcome", HttpStatus.OK_200, "Servlet at welcome extension"),
            new Data(false, REDIRECT, "/nothing/index.welcome", HttpStatus.OK_200, "Servlet at welcome extension"),
            new Data(false, REHANDLE, "/nothing/index.welcome", HttpStatus.OK_200, "Servlet at welcome extension"),
            new Data(true, SERVE, "/nothing/index.welcome", HttpStatus.OK_200, "Servlet at welcome extension"),
            new Data(true, REDIRECT, "/nothing/index.welcome", HttpStatus.OK_200, "Servlet at welcome extension"),
            new Data(true, REHANDLE, "/nothing/index.welcome", HttpStatus.OK_200, "Servlet at welcome extension"),

            new Data(false, SERVE, "/nothing/", HttpStatus.NOT_FOUND_404, null),
            new Data(false, REDIRECT, "/nothing/", HttpStatus.NOT_FOUND_404, null),
            new Data(false, REHANDLE, "/nothing/", HttpStatus.NOT_FOUND_404, null),
            new Data(true, SERVE, "/nothing/", HttpStatus.NOT_FOUND_404, null),
            new Data(true, REDIRECT, "/nothing/", HttpStatus.NOT_FOUND_404, null),
            new Data(true, REHANDLE, "/nothing/", HttpStatus.NOT_FOUND_404, null)
        );
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testCombinations(Data data) throws Exception
    {
        startServer(data.pathInfoOnly(), data.welcomeMode());
        String rawResponse = connector.getResponse(String.format("""
            GET %s HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """, data.requestPath()));
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        int status = response.getStatus();
        assertThat(response.toString(), status, is(data.expectedStatus()));
        if (status > 299 && status < 400)
            assertThat(response.get(HttpHeader.LOCATION), is(data.expected));
        else if (data.expected != null)
            assertThat(response.getContent(), is(data.expected));
    }
}
