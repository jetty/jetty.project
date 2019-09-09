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
import java.util.Base64;

import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class SecuredHelloHandlerTest
{
    private Server server;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = SecuredHelloHandler.createServer(0);
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testGetWithoutAuth() throws IOException
    {
        URI destUri = server.getURI().resolve("/hello");
        HttpURLConnection http = (HttpURLConnection)destUri.toURL().openConnection();
        assertThat("HTTP Response Status", http.getResponseCode(), is(HttpURLConnection.HTTP_UNAUTHORIZED));

        // HttpUtil.dumpResponseHeaders(http);
    }

    @Test
    public void testGetWithAuth() throws IOException
    {
        URI destUri = server.getURI().resolve("/hello");
        HttpURLConnection http = (HttpURLConnection)destUri.toURL().openConnection();
        String authEncoded = Base64.getEncoder().encodeToString("user:password".getBytes(UTF_8));
        http.setRequestProperty("Authorization", "Basic " + authEncoded);
        assertThat("HTTP Response Status", http.getResponseCode(), is(HttpURLConnection.HTTP_OK));

        // HttpUtil.dumpResponseHeaders(http);

        // test response content
        String responseBody = HttpUtil.getResponseBody(http);
        assertThat("Response Content", responseBody, containsString("<h1>Hello World</h1>"));
    }
}
