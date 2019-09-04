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
import java.net.HttpURLConnection;
import java.net.URI;

import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class ManyContextsTest
{
    private Server server;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = ManyContexts.createServer(0);
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testGetRootHello() throws IOException
    {
        URI uri = server.getURI().resolve("/");
        HttpURLConnection http = (HttpURLConnection)uri.toURL().openConnection();
        assertThat("HTTP Response Status", http.getResponseCode(), is(HttpURLConnection.HTTP_OK));

        // HttpUtil.dumpResponseHeaders(http);

        // test response content
        String responseBody = HttpUtil.getResponseBody(http);
        assertThat("Response Content", responseBody, containsString("Root Hello"));
    }

    @Test
    public void testGetFrenchHello() throws IOException
    {
        URI uri = server.getURI().resolve("/fr");
        HttpURLConnection http = (HttpURLConnection)uri.toURL().openConnection();
        assertThat("HTTP Response Status", http.getResponseCode(), is(HttpURLConnection.HTTP_OK));

        // HttpUtil.dumpResponseHeaders(http);

        // test response content
        String responseBody = HttpUtil.getResponseBody(http);
        assertThat("Response Content", responseBody, containsString("Bonjour"));
    }

    @Test
    public void testGetItalianGoodMorning() throws IOException
    {
        URI uri = server.getURI().resolve("/it");
        HttpURLConnection http = (HttpURLConnection)uri.toURL().openConnection();
        assertThat("HTTP Response Status", http.getResponseCode(), is(HttpURLConnection.HTTP_OK));

        // HttpUtil.dumpResponseHeaders(http);

        // test response content
        String responseBody = HttpUtil.getResponseBody(http);
        assertThat("Response Content", responseBody, containsString("Buongiorno"));
    }

    @Test
    public void testGetVirtualHostHello() throws IOException
    {
        int port = server.getURI().getPort();

        URI uri = URI.create("http://127.0.0.2:" + port + "/");
        HttpURLConnection http = (HttpURLConnection)uri.toURL().openConnection();
        assertThat("HTTP Response Status", http.getResponseCode(), is(HttpURLConnection.HTTP_OK));

        // HttpUtil.dumpResponseHeaders(http);

        // test response content
        String responseBody = HttpUtil.getResponseBody(http);
        assertThat("Response Content", responseBody, containsString("Virtual Hello"));
    }
}
