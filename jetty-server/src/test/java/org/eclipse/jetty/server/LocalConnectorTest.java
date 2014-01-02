//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.Connection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LocalConnectorTest
{
    private Server _server;
    private LocalConnector _connector;

    @Before
    public void prepare() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _connector.setIdleTimeout(60000);
        _server.addConnector(_connector);
        _server.setHandler(new DumpHandler());
        _server.start();
    }

    @After
    public void dispose() throws Exception
    {
        _server.stop();
        _server=null;
        _connector=null;
    }

    @Test
    public void testOpenClose() throws Exception
    {
        final CountDownLatch openLatch = new CountDownLatch(1);
        final CountDownLatch closeLatch = new CountDownLatch(1);
        _connector.addBean(new Connection.Listener.Adapter()
        {
            @Override
            public void onOpened(Connection connection)
            {
                openLatch.countDown();
            }

            @Override
            public void onClosed(Connection connection)
            {
                closeLatch.countDown();
            }
        });

        _connector.getResponses("" +
                "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");

        assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testOneGET() throws Exception
    {
        String response=_connector.getResponses("GET /R1 HTTP/1.0\r\n\r\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,containsString("pathInfo=/R1"));
    }

    @Test
    public void testStopStart() throws Exception
    {
        String response=_connector.getResponses("GET /R1 HTTP/1.0\r\n\r\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,containsString("pathInfo=/R1"));

        _server.stop();
        _server.start();

        response=_connector.getResponses("GET /R2 HTTP/1.0\r\n\r\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,containsString("pathInfo=/R2"));
    }

    @Test
    public void testTwoGETs() throws Exception
    {
        String response=_connector.getResponses(
            "GET /R1 HTTP/1.1\r\n"+
            "Host: localhost\r\n"+
            "\r\n"+
            "GET /R2 HTTP/1.0\r\n\r\n");

        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,containsString("pathInfo=/R1"));

        response=response.substring(response.indexOf("</html>")+8);

        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,containsString("pathInfo=/R2"));
    }

    @Test
    public void testGETandGET() throws Exception
    {
        String response=_connector.getResponses("GET /R1 HTTP/1.0\r\n\r\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,containsString("pathInfo=/R1"));

        response=_connector.getResponses("GET /R2 HTTP/1.0\r\n\r\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,containsString("pathInfo=/R2"));
    }
}
