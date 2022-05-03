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

package org.eclipse.jetty.ee10.jstl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URI;

import org.eclipse.jetty.ee10.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee10.webapp.Configurations;
import org.eclipse.jetty.ee10.webapp.JettyWebXmlConfiguration;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.JAR;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class JspIncludeTest
{
    private static Server server;
    private static URI baseUri;

    @BeforeAll
    public static void startServer() throws Exception
    {
        // Setup Server
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        // Setup WebAppContext
        File testWebAppDir = MavenTestingUtils.getProjectDir("src/test/webapp");

        // Prepare WebApp libs
        File libDir = new File(testWebAppDir, "WEB-INF/lib");
        FS.ensureDirExists(libDir);
        File testTagLibDir = MavenTestingUtils.getProjectDir("src/test/taglibjar");
        JAR.create(testTagLibDir, new File(libDir, "testtaglib.jar"));

        // Configure WebAppContext
        Configurations.setServerDefault(server).add(new JettyWebXmlConfiguration(), new AnnotationConfiguration());

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");

        File scratchDir = MavenTestingUtils.getTargetFile("tests/" + JspIncludeTest.class.getSimpleName() + "-scratch");
        FS.ensureEmpty(scratchDir);
        JspConfig.init(context, testWebAppDir.toURI(), scratchDir);

        server.setHandler(context);

        // Start Server
        server.start();

        // Figure out Base URI
        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        baseUri = new URI(String.format("http://%s:%d/", host, port));
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Disabled //TODO
    @Test
    public void testTopWithIncluded() throws IOException
    {
        URI uri = baseUri.resolve("/top.jsp");
        // System.out.println("GET (String): " + uri.toASCIIString());

        InputStream in = null;
        InputStreamReader reader = null;
        HttpURLConnection connection = null;

        try
        {
            connection = (HttpURLConnection)uri.toURL().openConnection();
            connection.connect();
            if (HttpURLConnection.HTTP_OK != connection.getResponseCode())
            {
                String body = getPotentialBody(connection);
                String err = String.format("GET request failed (%d %s) %s%n%s", connection.getResponseCode(), connection.getResponseMessage(),
                    uri.toASCIIString(), body);
                throw new IOException(err);
            }
            in = connection.getInputStream();
            reader = new InputStreamReader(in);
            StringWriter writer = new StringWriter();
            IO.copy(reader, writer);

            String response = writer.toString();
            // System.out.printf("Response%n%s",response);
            assertThat("Response", response, containsString("<h2> Hello, this is the top page."));
            assertThat("Response", response, containsString("<h3> This is the included page"));

            assertThat("Response Header[main-page-key]", connection.getHeaderField("main-page-key"), is("main-page-value"));
            assertThat("Response Header[included-page-key]", connection.getHeaderField("included-page-key"), is("included-page-value"));
        }
        finally
        {
            IO.close(reader);
            IO.close(in);
        }
    }

    /**
     * Attempt to obtain the body text if available. Do not throw an exception if body is unable to be fetched.
     *
     * @param connection the connection to fetch the body content from.
     * @return the body content, if present.
     */
    private String getPotentialBody(HttpURLConnection connection)
    {
        InputStream in = null;
        InputStreamReader reader = null;
        try
        {
            in = connection.getInputStream();
            reader = new InputStreamReader(in);
            StringWriter writer = new StringWriter();
            IO.copy(reader, writer);
            return writer.toString();
        }
        catch (IOException e)
        {
            return "<no body:" + e.getMessage() + ">";
        }
        finally
        {
            IO.close(reader);
            IO.close(in);
        }
    }
}
