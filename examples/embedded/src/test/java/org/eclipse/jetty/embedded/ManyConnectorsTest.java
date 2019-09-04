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
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class ManyConnectorsTest
{
    private Server server;
    private URI serverPlainUri;
    private URI serverSslUri;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = ManyConnectors.createServer(0, 0);
        server.start();

        System.err.println("Server URI is " + server.getURI());

        Map<String, Integer> ports = ServerUtil.fixDynamicPortConfigurations(server);

        // Establish base URI's that use "localhost" to prevent tripping over
        // the "REMOTE ACCESS" warnings in demo-base
        serverPlainUri = URI.create("http://localhost:" + ports.get("plain") + "/");
        serverSslUri = URI.create("https://localhost:" + ports.get("secure") + "/");
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testPlainGetHello() throws IOException
    {
        URI helloUri = serverPlainUri.resolve("/hello");

        HttpURLConnection http = (HttpURLConnection)helloUri.toURL().openConnection();
        assertThat("HTTP Response Status", http.getResponseCode(), is(HttpURLConnection.HTTP_OK));

        // HttpUtil.dumpResponseHeaders(http);

        // test response content
        String responseBody = HttpUtil.getResponseBody(http);
        assertThat("Response Content", responseBody, containsString("Hello"));
    }

    @Test
    public void testSecureGetHello() throws Exception
    {
        HttpUtil.disableSecureConnectionVerification();
        URI helloUri = serverSslUri.resolve("/hello");

        HttpsURLConnection https = (HttpsURLConnection)helloUri.toURL().openConnection();
        assertThat("HTTPS Response Status", https.getResponseCode(), is(HttpURLConnection.HTTP_OK));

        // HttpUtil.dumpResponseHeaders(http);

        // test response content
        String responseBody = HttpUtil.getResponseBody(https);
        assertThat("Response Content", responseBody, containsString("Hello"));
    }
}
