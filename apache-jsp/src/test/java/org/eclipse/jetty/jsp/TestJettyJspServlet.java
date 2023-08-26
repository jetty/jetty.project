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

package org.eclipse.jetty.jsp;

import org.apache.jasper.runtime.JspFactoryImpl;
import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.SimpleInstanceManager;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.jsp.JspFactory;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@ExtendWith(WorkDirExtension.class)
public class TestJettyJspServlet
{
    private static final String DEFAULT_RESP_OUTPUT = "This.Is.The.Default";

    public WorkDir workdir;
    private Server _server;
    private LocalConnector _connector;

    public static class DfltServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            resp.setContentType("html/text");
            resp.getOutputStream().println(DEFAULT_RESP_OUTPUT);
        }
    }

    @BeforeEach
    public void setUp() throws Exception
    {
        JspFactory.setDefaultFactory(new JspFactoryImpl());
        final Path path = Path.of("src", "java", "resources", "base").toAbsolutePath();
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);
        ServletContextHandler context = new ServletContextHandler(_server, "/context", true, false);
        context.setClassLoader(new URLClassLoader(new URL[0], Thread.currentThread().getContextClassLoader()));
        ServletHolder jspHolder = context.addServlet(JettyJspServlet.class, "/*");
        jspHolder.setInitParameter("scratchdir", workdir.getPath().toString());
        context.setResourceBase(path.toString());
        context.setAttribute(InstanceManager.class.getName(), new SimpleInstanceManager());
        ServletHolder dfltHolder = new ServletHolder();
        dfltHolder.setName("default");
        dfltHolder.setHeldClass(DfltServlet.class);
        context.addServlet(dfltHolder, "/");
        _server.start();
    }

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(_server);
    }


    @ParameterizedTest
    @ValueSource(strings = {"foo.jsp", "dir"})
    public void checkIsServed(String urlPath) throws Exception
    {
        String request =
            "GET /context/" + urlPath + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";

        String rawResponse = _connector.getResponse(request);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getContent(), not(containsString(DEFAULT_RESP_OUTPUT)));
    }

}
