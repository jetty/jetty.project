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
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ajax.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class ManyHandlersTest
{
    private Server server;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = ManyHandlers.createServer(0);
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testGetParams() throws IOException
    {
        URI uri = server.getURI().resolve("/params?a=b&foo=bar");
        HttpURLConnection http = (HttpURLConnection)uri.toURL().openConnection();
        http.setRequestProperty("Accept-Encoding", "gzip");
        assertThat("HTTP Response Status", http.getResponseCode(), is(HttpURLConnection.HTTP_OK));

        // dumpResponseHeaders(http);

        // test gzip
        assertGzippedResponse(http);

        // test response content
        String responseBody = getResponseBody(http);
        Object jsonObj = JSON.parse(responseBody);
        Map jsonMap = (Map)jsonObj;
        assertThat("Response JSON keys.size", jsonMap.keySet().size(), is(2));
    }

    @Test
    public void testGetHello() throws IOException
    {
        URI uri = server.getURI().resolve("/hello");
        HttpURLConnection http = (HttpURLConnection)uri.toURL().openConnection();
        http.setRequestProperty("Accept-Encoding", "gzip");
        assertThat("HTTP Response Status", http.getResponseCode(), is(HttpURLConnection.HTTP_OK));

        // dumpResponseHeaders(http);

        // test gzip
        assertGzippedResponse(http);

        // test expected header from wrapper
        String welcome = http.getHeaderField("X-Welcome");
        assertThat("X-Welcome header", welcome, containsString("Greetings from WelcomeWrapHandler"));

        // test response content
        String responseBody = getResponseBody(http);
        assertThat("Response Content", responseBody, containsString("Hello"));
    }

    private void assertGzippedResponse(HttpURLConnection http)
    {
        String value = http.getHeaderField("Content-Encoding");
        assertThat("Content-Encoding", value, containsString("gzip"));
    }

    private String getResponseBody(HttpURLConnection http) throws IOException
    {
        try (InputStream in = http.getInputStream();
             GZIPInputStream gzipInputStream = new GZIPInputStream(in))
        {
            return IO.toString(gzipInputStream, UTF_8);
        }
    }

    @SuppressWarnings("unused")
    private void dumpResponseHeaders(HttpURLConnection http)
    {
        int i = 0;
        while (true)
        {
            String field = http.getHeaderField(i);
            if (field == null)
                return;
            String key = http.getHeaderFieldKey(i);
            if (key != null)
            {
                System.out.printf("%s: ", key);
            }
            System.out.println(field);
            i++;
        }
    }
}
