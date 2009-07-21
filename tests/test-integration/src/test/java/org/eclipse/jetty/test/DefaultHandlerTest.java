// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;

import org.eclipse.jetty.http.HttpSchemes;
import org.eclipse.jetty.test.support.TestableJettyServer;
import org.eclipse.jetty.test.support.rawhttp.HttpRequestTester;
import org.eclipse.jetty.test.support.rawhttp.HttpResponseTester;
import org.eclipse.jetty.test.support.rawhttp.HttpSocketImpl;
import org.eclipse.jetty.test.support.rawhttp.HttpTesting;
import org.eclipse.jetty.util.IO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests against the facilities within the TestSuite to ensure that the various
 * org.eclipse.jetty.test.support.* classes do what they are supposed to.
 */
public class DefaultHandlerTest extends AbstractJettyTestCase
{
    private boolean debug = true;
    private TestableJettyServer server;
    private int serverPort;

    @Override
    @Before
    public void setUp() throws Exception
    {
        super.setUp();
        
        server = new TestableJettyServer();
        server.setScheme(HttpSchemes.HTTP);
        server.addConfiguration("DefaultHandler.xml");

        server.load();
        server.start();
        serverPort = server.getServerPort();
    }

    @Override
    @After
    public void tearDown() throws Exception
    {
        server.stop();
        super.tearDown();
    }

    @Test
    public void testGET_URL() throws Exception
    {
        URL url = new URL("http://localhost:" + serverPort + "/tests/alpha.txt");
        URLConnection conn = url.openConnection();
        conn.connect();

        InputStream in = conn.getInputStream();

        String response = IO.toString(in);
        String expected = "ABCDEFGHIJKLMNOPQRSTUVWXYZ\n";
        
        assertEquals("Response",expected,response);
    }

    @Test
    public void testGET_Raw() throws Exception
    {
        StringBuffer rawRequest = new StringBuffer();
        rawRequest.append("GET /tests/alpha.txt HTTP/1.1\r\n");
        rawRequest.append("Host: localhost\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        Socket sock = new Socket(InetAddress.getLocalHost(),serverPort);
        sock.setSoTimeout(5000); // 5 second timeout;

        DEBUG("--raw-request--\n" + rawRequest);
        InputStream in = new ByteArrayInputStream(rawRequest.toString().getBytes());

        // Send request
        IO.copy(in,sock.getOutputStream());

        // Collect response
        String rawResponse = IO.toString(sock.getInputStream());
        DEBUG("--raw-response--\n" + rawResponse);
        HttpResponseTester response = new HttpResponseTester();
        response.parse(rawResponse);

        response.assertStatusOK();

        response.assertBody("ABCDEFGHIJKLMNOPQRSTUVWXYZ\n");
    }

    @Test
    public void testMultiGET_Raw() throws Exception
    {
        StringBuffer rawRequests = new StringBuffer();
        rawRequests.append("GET /tests/alpha.txt HTTP/1.1\r\n");
        rawRequests.append("Host: localhost\r\n");
        rawRequests.append("\r\n");
        rawRequests.append("GET /tests/R1.txt HTTP/1.1\r\n");
        rawRequests.append("Host: localhost\r\n");
        rawRequests.append("\r\n");
        rawRequests.append("GET /tests/R1.txt HTTP/1.1\r\n");
        rawRequests.append("Host: localhost\r\n");
        rawRequests.append("Connection: close\r\n");
        rawRequests.append("\r\n");

        HttpTesting http = new HttpTesting(new HttpSocketImpl(),serverPort);

        List<HttpResponseTester> responses = http.requests(rawRequests);

        HttpResponseTester response = responses.get(0);
        response.assertStatusOK();
        response.assertBody("ABCDEFGHIJKLMNOPQRSTUVWXYZ\n");

        response = responses.get(1);
        response.assertStatusOK();
        response.assertBody("Host=Default\nResource=R1\n");

        response = responses.get(2);
        response.assertStatusOK();
        response.assertBody("Host=Default\nResource=R1\n");
    }

    @Test
    public void testGET_HttpTesting() throws Exception
    {
        HttpRequestTester request = new HttpRequestTester();
        request.setMethod("GET");
        request.setURI("/tests/alpha.txt");
        request.addHeader("Host","localhost");
        request.addHeader("Connection","close");
        // request.setContent(null);

        HttpTesting testing = new HttpTesting(new HttpSocketImpl(),serverPort);
        HttpResponseTester response = testing.request(request);

        response.assertStatusOK();
        response.assertContentType("text/plain");
        response.assertBody("ABCDEFGHIJKLMNOPQRSTUVWXYZ\n");
    }

    private void DEBUG(String msg)
    {
        if (debug)
        {
            System.out.println(msg);
        }
    }
}
