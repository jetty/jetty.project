//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.cdi.servlet;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.JettyLogHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class WeldInitializationTest
{
    private static final Logger LOG = Log.getLogger(WeldInitializationTest.class);
    private static Server server;
    private static URI serverHttpURI;

    @BeforeClass
    public static void startServer() throws Exception
    {
        JettyLogHandler.config();

        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        EmbeddedCdiHandler context = new EmbeddedCdiHandler(ServletContextHandler.SESSIONS);

        File baseDir = MavenTestingUtils.getTestResourcesDir();

        context.setBaseResource(Resource.newResource(baseDir));
        context.setContextPath("/");
        server.setHandler(context);

        // Add some servlets
        context.addServlet(TimeServlet.class,"/time");
        context.addServlet(RequestInfoServlet.class,"/req-info");

        server.start();

        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        serverHttpURI = new URI(String.format("http://%s:%d/",host,port));
    }

    @AfterClass
    public static void stopServer()
    {
        try
        {
            server.stop();
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }

    @Test
    public void testRequestParamServletDefault() throws Exception
    {
        HttpURLConnection http = (HttpURLConnection) serverHttpURI.resolve("req-info").toURL().openConnection();
        assertThat("response code", http.getResponseCode(), is(200));
        try(InputStream inputStream = http.getInputStream())
        {
            String resp = IO.toString(inputStream);
            assertThat("Response", resp, containsString("request is PRESENT"));
            assertThat("Response", resp, containsString("parameters.size = [0]"));
        }
    }

    @Test
    public void testRequestParamServletAbc() throws Exception
    {
        HttpURLConnection http = (HttpURLConnection) serverHttpURI.resolve("req-info?abc=123").toURL().openConnection();
        assertThat("response code", http.getResponseCode(), is(200));
        try(InputStream inputStream = http.getInputStream())
        {
            String resp = IO.toString(inputStream);
            assertThat("Response", resp, containsString("request is PRESENT"));
            assertThat("Response", resp, containsString("parameters.size = [1]"));
            assertThat("Response", resp, containsString(" param[abc] = [123]"));
        }
    }
}
