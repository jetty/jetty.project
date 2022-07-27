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

package org.eclipse.jetty.ee9.jsp;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.jsp.JspFactory;
import org.apache.jasper.runtime.JspFactoryImpl;
import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.SimpleInstanceManager;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@ExtendWith(WorkDirExtension.class)
public class TestJettyJspServlet
{
    public WorkDir workdir;
    private Server _server;
    private LocalConnector _connector;

    public static class DfltServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            resp.setContentType("html/text");
            resp.getOutputStream().println("This.Is.The.Default.");
        }
    }

    @BeforeEach
    public void setUp() throws Exception
    {
        JspFactory.setDefaultFactory(new JspFactoryImpl());
        File baseDir = MavenTestingUtils.getTargetPath("test-classes/base").toFile();
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);
        ServletContextHandler context = new ServletContextHandler(_server, "/context", true, false);
        context.setClassLoader(new URLClassLoader(new URL[0], Thread.currentThread().getContextClassLoader()));
        ServletHolder jspHolder = context.addServlet(JettyJspServlet.class, "/*");
        jspHolder.setInitParameter("scratchdir", workdir.getPath().toString());
        context.setResourceBase(baseDir.getAbsolutePath());
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

    @Test
    public void testWithJsp() throws Exception
    {
        //test that an ordinary jsp is served by jsp servlet
        String request =
            "GET /context/foo.jsp HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";

        String rawResponse = _connector.getResponse(request);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getContent(), not(containsString("This.Is.The.Default.")));
    }

    @Test
    public void testWithDirectory() throws Exception
    {
        //test that a dir is served by the default servlet
        String request =
            "GET /context/dir HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        String rawResponse = _connector.getResponse(request);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getContent(), containsString("This.Is.The.Default."));
    }
}
