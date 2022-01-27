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

package org.eclipse.jetty.test.jsp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Test various paths for JSP resources that tickle various java.io.File bugs to get around the JspServlet matching, that then flows to the DefaultServlet to be
 * served as source files.
 */
public class JspAndDefaultWithAliasesTest
{
    private static final Logger LOG = LoggerFactory.getLogger(JspAndDefaultWithAliasesTest.class);
    private static Server server;
    private static URI serverURI;

    public static Stream<Arguments> aliases()
    {
        List<Arguments> data = new ArrayList<>();

        data.add(Arguments.of("false", "/dump.jsp"));
        data.add(Arguments.of("false", "/dump.jsp/"));
        data.add(Arguments.of("true", "/dump.jsp%00"));
        data.add(Arguments.of("false", "/dump.jsp%00/"));
        data.add(Arguments.of("false", "/dump.jsp%00x/dump.jsp"));
        data.add(Arguments.of("false", "/dump.jsp%00/dump.jsp"));
        data.add(Arguments.of("false", "/dump.jsp%00x"));
        data.add(Arguments.of("false", "/dump.jsp%00x/"));
        data.add(Arguments.of("false", "/dump.jsp%00/index.html"));

        return data.stream();
    }

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new Server(0);

        // Configure LoginService
        HashLoginService login = new HashLoginService();
        login.setName("Test Realm");
        File realmFile = MavenTestingUtils.getTestResourceFile("realm.properties");
        login.setConfig(realmFile.getAbsolutePath());
        server.addBean(login);

        // Configure WebApp
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        File webappBase = MavenTestingUtils.getTestResourceDir("docroots/jsp");
        context.setResourceBase(webappBase.getAbsolutePath());
        context.setClassLoader(Thread.currentThread().getContextClassLoader());

        // add default servlet
        ServletHolder defaultServHolder = context.addServlet(DefaultServlet.class, "/");
        defaultServHolder.setInitParameter("aliases", "true"); // important! must be TRUE

        // add jsp
        ServletHolder jsp = new ServletHolder(new FakeJspServlet());
        context.addServlet(jsp, "*.jsp");
        jsp.setInitParameter("classpath", context.getClassPath());

        // add context
        server.setHandler(context);

        server.start();

        int port = ((NetworkConnector)server.getConnectors()[0]).getLocalPort();

        serverURI = new URI("http://localhost:" + port + "/");
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    private void assertProcessedByJspServlet(HttpURLConnection conn, String path, boolean knownBypass) throws IOException
    {
        // make sure that jsp actually ran, and didn't just get passed onto
        // the default servlet to return the jsp source
        String body = getResponseBody(conn);

        if (knownBypass && body.indexOf("<%@") >= 0)
            LOG.info("Known bypass of mapping by " + path);
        else
        {
            assertThat("Body", body, not(containsString("<%@")));
            assertThat("Body", body, not(containsString("<jsp:")));
        }
    }

    private void assertResponse(HttpURLConnection conn, String path, boolean knownBypass) throws IOException
    {
        if (conn.getResponseCode() == 200)
        {
            // Serving content is allowed, but it better be the processed JspServlet
            assertProcessedByJspServlet(conn, path, knownBypass);
            return;
        }

        if (conn.getResponseCode() != 404)
            System.err.println(conn.getResponseMessage());

        // Of other possible paths, only 404 Not Found is expected
        assertThat("Response Code", conn.getResponseCode(), is(404));
    }

    @ParameterizedTest
    @MethodSource("aliases")
    public void testGetReference(String path, boolean knownBypass) throws Exception
    {
        URI uri = serverURI.resolve(path);

        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection)uri.toURL().openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            assertResponse(conn, path, knownBypass);
        }
        finally
        {
            conn.disconnect();
        }
    }

    protected String getResponseBody(HttpURLConnection conn) throws IOException
    {
        InputStream in = null;
        try
        {
            in = conn.getInputStream();
            return IO.toString(in);
        }
        finally
        {
            IO.close(in);
        }
    }
}
