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

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringBufferInputStream;
import java.net.HttpURLConnection;
import java.net.URI;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class ExampleServerTest
{
    private Server server;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = ExampleServer.createServer(0);
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
        URI uri = server.getURI().resolve("/hello");
        HttpURLConnection http = (HttpURLConnection)uri.toURL().openConnection();
        assertThat("HTTP Response Status", http.getResponseCode(), is(HttpURLConnection.HTTP_OK));

        // HttpUtil.dumpResponseHeaders(http);

        // test response content
        String responseBody = HttpUtil.getResponseBody(http);
        assertThat("Response Content", responseBody, containsString("Hello"));
    }

    @Test
    public void testGetEcho() throws IOException
    {
        URI uri = server.getURI().resolve("/echo/a/greeting");
        HttpURLConnection http = (HttpURLConnection)uri.toURL().openConnection();

        // Use a POST request
        http.setRequestMethod("POST");
        http.setDoOutput(true);

        // The POST body content
        String postBody = "Greetings from " + ExampleServerTest.class;
        http.setRequestProperty("Content-Type", "text/plain; charset=utf-8");

        try (StringBufferInputStream in = new StringBufferInputStream(postBody);
             OutputStream out = http.getOutputStream())
        {
            IO.copy(in, out);
        }

        // Check the response status code
        assertThat("HTTP Response Status", http.getResponseCode(), is(HttpURLConnection.HTTP_OK));

        // HttpUtil.dumpResponseHeaders(http);

        // test response content
        String responseBody = HttpUtil.getResponseBody(http);
        assertThat("Response Content", responseBody, containsString(postBody));
    }
}
