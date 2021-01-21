//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.test.support.TestableJettyServer;
import org.eclipse.jetty.test.support.rawhttp.HttpSocketImpl;
import org.eclipse.jetty.test.support.rawhttp.HttpTesting;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.http.HttpFieldsMatchers.containsHeaderValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests against the facilities within the TestSuite to ensure that the various
 * org.eclipse.jetty.test.support.* classes do what they are supposed to.
 */
public class DefaultHandlerTest
{
    private static TestableJettyServer server;
    private int serverPort;

    @BeforeAll
    public static void setUpServer() throws Exception
    {
        server = new TestableJettyServer();
        server.setScheme(HttpScheme.HTTP.asString());
        server.addConfiguration("DefaultHandler.xml");
        server.addConfiguration("NIOHttp.xml");

        server.load();
        server.start();
    }

    @BeforeEach
    public void testInit()
    {
        serverPort = server.getServerPort();
    }

    @AfterAll
    public static void tearDownServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testGetURL() throws Exception
    {
        URL url = new URL("http://localhost:" + serverPort + "/tests/alpha.txt");
        URLConnection conn = url.openConnection();
        conn.connect();

        InputStream in = conn.getInputStream();

        String response = IO.toString(in);
        String expected = "ABCDEFGHIJKLMNOPQRSTUVWXYZ\n";

        assertEquals(expected, response, "Response");
    }

    @Test
    public void testGetRaw() throws Exception
    {
        StringBuffer rawRequest = new StringBuffer();
        rawRequest.append("GET /tests/alpha.txt HTTP/1.1\r\n");
        rawRequest.append("Host: localhost\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        Socket sock = new Socket(InetAddress.getLocalHost(), serverPort);
        sock.setSoTimeout(5000); // 5 second timeout;

        InputStream in = new ByteArrayInputStream(rawRequest.toString().getBytes());

        // Send request
        IO.copy(in, sock.getOutputStream());

        // Collect response
        String rawResponse = IO.toString(sock.getInputStream());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("ABCDEFGHIJKLMNOPQRSTUVWXYZ\n"));
    }

    @Test
    public void testGetHttpTesting() throws Exception
    {
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setURI("/tests/alpha.txt");
        request.put("Host", "localhost");
        request.put("Connection", "close");
        // request.setContent(null);

        HttpTesting testing = new HttpTesting(new HttpSocketImpl(), serverPort);
        HttpTester.Response response = testing.request(request);

        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response.getContent(), containsString("ABCDEFGHIJKLMNOPQRSTUVWXYZ\n"));
    }
}
