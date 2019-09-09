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

public class OneWebAppWithJspTest
{
    private Server server;
    private URI serverLocalUri;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = OneWebAppWithJsp.createServer(0);
        server.start();

        // Use URI based on "localhost" to get past "REMOTE ACCESS!" protection of demo war
        serverLocalUri = URI.create("http://localhost:" + server.getURI().getPort() + "/");
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testGetDumpInfo() throws IOException
    {
        URI uri = serverLocalUri.resolve("/dump/info");
        HttpURLConnection http = (HttpURLConnection)uri.toURL().openConnection();
        assertThat("HTTP Response Status", http.getResponseCode(), is(HttpURLConnection.HTTP_OK));

        HttpUtil.dumpResponseHeaders(http);

        // test response content
        String responseBody = HttpUtil.getResponseBody(http);
        assertThat("Response Content", responseBody, containsString("getProtocol:&nbsp;</th><td>HTTP/1.1"));
    }

    @Test
    public void testGetJspExpr() throws IOException
    {
        URI uri = serverLocalUri.resolve("/jsp/expr.jsp?A=1");
        HttpURLConnection http = (HttpURLConnection)uri.toURL().openConnection();
        String userAgent = OneWebAppWithJspTest.class.getSimpleName();
        http.setRequestProperty("User-Agent", userAgent);
        assertThat("HTTP Response Status", http.getResponseCode(), is(HttpURLConnection.HTTP_OK));

        // HttpUtil.dumpResponseHeaders(http);

        // test response content
        String responseBody = HttpUtil.getResponseBody(http);
        assertThat("Response Content", responseBody, containsString("<td>" + userAgent + "</td>"));
    }

    @Test
    public void testGetJstlExpr() throws IOException
    {
        URI uri = serverLocalUri.resolve("/jsp/jstl.jsp");
        HttpURLConnection http = (HttpURLConnection)uri.toURL().openConnection();
        assertThat("HTTP Response Status", http.getResponseCode(), is(HttpURLConnection.HTTP_OK));

        // HttpUtil.dumpResponseHeaders(http);

        // test response content
        String responseBody = HttpUtil.getResponseBody(http);
        assertThat("Response Content", responseBody, containsString("<h1>JSTL Example</h1>"));
        for (int i = 1; i <= 10; i++)
        {
            assertThat("Reponse content (counting)", responseBody, containsString("" + i));
        }
    }
}
