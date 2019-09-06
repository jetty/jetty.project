//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.embedded;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.resource.PathResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

@ExtendWith(WorkDirExtension.class)
public class OneServletContextTest
{
    private static final String TEXT_CONTENT = "The secret of getting ahead is getting started. - Mark Twain";
    public WorkDir workDir;
    private Server server;

    @BeforeEach
    public void startServer() throws Exception
    {
        Path baseDir = workDir.getEmptyPathDir();

        Path textFile = baseDir.resolve("simple.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(textFile, UTF_8))
        {
            writer.write(TEXT_CONTENT);
        }

        server = OneServletContext.createServer(0, new PathResource(baseDir));
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testGetHello() throws IOException
    {
        URI uri = server.getURI().resolve("/hello/there");
        HttpURLConnection http = (HttpURLConnection)uri.toURL().openConnection();
        assertThat("HTTP Response Status", http.getResponseCode(), is(HttpURLConnection.HTTP_OK));

        // HttpUtil.dumpResponseHeaders(http);

        // test response content
        String responseBody = HttpUtil.getResponseBody(http);
        assertThat("Response Content", responseBody, containsString("Hello"));
    }

    @Test
    public void testGetDumpViaPathInfo() throws IOException
    {
        URI uri = server.getURI().resolve("/dump/something");
        HttpURLConnection http = (HttpURLConnection)uri.toURL().openConnection();
        assertThat("HTTP Response Status", http.getResponseCode(), is(HttpURLConnection.HTTP_OK));

        // HttpUtil.dumpResponseHeaders(http);

        // test response content
        String responseBody = HttpUtil.getResponseBody(http);
        assertThat("Response Content", responseBody,
            allOf(
                containsString("DumpServlet"),
                containsString("servletPath=/dump"),
                containsString("pathInfo=/something")
            )
        );
    }

    @Test
    public void testGetDumpSuffix() throws IOException
    {
        URI uri = server.getURI().resolve("/another.dump");
        HttpURLConnection http = (HttpURLConnection)uri.toURL().openConnection();
        assertThat("HTTP Response Status", http.getResponseCode(), is(HttpURLConnection.HTTP_OK));

        // HttpUtil.dumpResponseHeaders(http);

        // test response content
        String responseBody = HttpUtil.getResponseBody(http);
        assertThat("Response Content", responseBody,
            allOf(
                containsString("DumpServlet"),
                containsString("servletPath=/another.dump"),
                containsString("pathInfo=null")
            )
        );
    }

    @Test
    public void testGetTestDumpSuffix() throws IOException
    {
        URI uri = server.getURI().resolve("/test/another.dump");
        HttpURLConnection http = (HttpURLConnection)uri.toURL().openConnection();
        assertThat("HTTP Response Status", http.getResponseCode(), is(HttpURLConnection.HTTP_OK));

        // HttpUtil.dumpResponseHeaders(http);

        String filterResponeHeader = http.getHeaderField("X-TestFilter");
        assertThat("X-TestFilter header", filterResponeHeader, is("true"));

        // test response content
        String responseBody = HttpUtil.getResponseBody(http);
        assertThat("Response Content", responseBody,
            allOf(
                containsString("DumpServlet"),
                containsString("servletPath=/test/another.dump"),
                containsString("pathInfo=null"),
                containsString("request.attribute[X-ReqListener]=true"),
                containsString("servletContext.attribute[X-Init]=true")
            )
        );
    }
}
