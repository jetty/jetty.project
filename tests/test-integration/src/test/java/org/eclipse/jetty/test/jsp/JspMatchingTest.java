//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.test.jsp;

import static org.hamcrest.Matchers.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;

import org.apache.jasper.servlet.JspServlet;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Loader;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

public class JspMatchingTest
{
    private static Server server;
    private static URI serverURI;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setPort(0);
        server.addConnector(connector);

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
        URLClassLoader contextLoader = new URLClassLoader(new URL[]{}, Server.class.getClassLoader());
        context.setClassLoader(contextLoader);

        // add default servlet
        ServletHolder defaultServHolder = context.addServlet(DefaultServlet.class,"/");
        defaultServHolder.setInitParameter("aliases","true"); // important!

        // add jsp
        ServletHolder jsp = context.addServlet(JspServlet.class,"*.jsp");
        context.setAttribute("org.apache.catalina.jsp_classpath", context.getClassPath());
        jsp.setInitParameter("com.sun.appserv.jsp.classpath", Loader.getClassPath(Server.class.getClassLoader()));

        // add context
        server.setHandler(context);

        server.start();

        serverURI = new URI("http://localhost:" + connector.getLocalPort() + "/");

    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testGetBeanRef() throws Exception
    {

        URI uri = serverURI.resolve("/dump.jsp");

        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection)uri.toURL().openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            Assert.assertThat(conn.getResponseCode(),is(200));

            // make sure that jsp actually ran, and didn't just get passed onto
            // the default servlet to return the jsp source
            String body = getResponseBody(conn);
            Assert.assertThat("Body",body,not(containsString("<%@")));
            Assert.assertThat("Body",body,not(containsString("<jsp:")));
        }
        finally
        {
            close(conn);
        }
    }

    @Test
    public void testGetBeanRefInvalid_null() throws Exception
    {

        URI uri = serverURI.resolve("/dump.jsp%00");

        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection)uri.toURL().openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            Assert.assertThat("Response Code",conn.getResponseCode(),is(404));
        }
        finally
        {
            close(conn);
        }
    }

    @Ignore("DefaultServlet + aliasing breaks this test ATM")
    @Test
    public void testGetBeanRefInvalid_nullx() throws Exception
    {

        URI uri = serverURI.resolve("/dump.jsp%00x");

        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection)uri.toURL().openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            Assert.assertThat("Response Code",conn.getResponseCode(),is(404));
        }
        finally
        {
            close(conn);
        }
    }

    @Ignore("DefaultServlet + aliasing breaks this test ATM")
    @Test
    public void testGetBeanRefInvalid_nullslash() throws Exception
    {

        URI uri = serverURI.resolve("/dump.jsp%00/");

        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection)uri.toURL().openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            Assert.assertThat("Response Code",conn.getResponseCode(),is(404));
        }
        finally
        {
            close(conn);
        }
    }

    @Ignore("DefaultServlet + aliasing breaks this test ATM")
    @Test
    public void testGetBeanRefInvalid_nullxslash() throws Exception
    {

        URI uri = serverURI.resolve("/dump.jsp%00x/");

        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection)uri.toURL().openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            Assert.assertThat("Response Code",conn.getResponseCode(),is(404));
        }
        finally
        {
            close(conn);
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

    private void close(HttpURLConnection conn)
    {
        conn.disconnect();
    }
}
