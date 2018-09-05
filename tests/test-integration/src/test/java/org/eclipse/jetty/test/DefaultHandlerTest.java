//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.test.support.TestableJettyServer;
import org.eclipse.jetty.test.support.rawhttp.HttpSocketImpl;
import org.eclipse.jetty.test.support.rawhttp.HttpTesting;
import org.eclipse.jetty.util.IO;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests against the facilities within the TestSuite to ensure that the various
 * org.eclipse.jetty.test.support.* classes do what they are supposed to.
 */
public class DefaultHandlerTest
{
    private boolean debug = false;
    private static TestableJettyServer server;
    private int serverPort;

    @BeforeClass
    public static void setUpServer() throws Exception
    {
        server = new TestableJettyServer();
        server.setScheme(HttpScheme.HTTP.asString());
        server.addConfiguration("DefaultHandler.xml");
        server.addConfiguration("NIOHttp.xml");

        server.load();
        server.start();
    }
    
    @Before
    public void testInit() {
        serverPort = server.getServerPort();
    }

    @AfterClass
    public static void tearDownServer() throws Exception
    {
        server.stop();
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
        
        Assert.assertEquals("Response",expected,response);
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
        
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertEquals(HttpStatus.OK_200, response.getStatus());

        assertTrue(response.getContent().contains("ABCDEFGHIJKLMNOPQRSTUVWXYZ\n"));
    }

    /*
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
      

        List<HttpTester.Response> responses = http.requests(rawRequests);

        HttpTester.Response response = responses.get(0);
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(response.getContent().contains("ABCDEFGHIJKLMNOPQRSTUVWXYZ\n"));

        response = responses.get(1);
        assertEquals(HttpStatus.OK_200, response.getStatus()); 
        assertTrue(response.getContent().contains("Host=Default\nResource=R1\n"));

        response = responses.get(2);
        assertEquals(HttpStatus.OK_200, response.getStatus()); 
        assertTrue(response.getContent().contains("Host=Default\nResource=R1\n"));
    }
    */
    
    
    

    @Test
    public void testGET_HttpTesting() throws Exception
    {
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setURI("/tests/alpha.txt");
        request.put("Host","localhost");
        request.put("Connection","close");
        // request.setContent(null);

        HttpTesting testing = new HttpTesting(new HttpSocketImpl(),serverPort);
        HttpTester.Response response = testing.request(request);

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals("text/plain", response.get("Content-Type"));
        assertTrue(response.getContent().contains("ABCDEFGHIJKLMNOPQRSTUVWXYZ\n"));
    }

    private void DEBUG(String msg)
    {
        if (debug)
        {
            System.out.println(msg);
        }
    }
}
