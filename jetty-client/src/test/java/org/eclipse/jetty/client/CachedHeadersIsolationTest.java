//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.Enumeration;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/* ------------------------------------------------------------ */
public class CachedHeadersIsolationTest
{

    Server server;
    HttpClient client;
    int port;

    @Before
    public void setUp() throws Exception
    {
        server = new Server();

        Connector connector = new SelectChannelConnector();

        server.addConnector(connector);

        server.setHandler(new AbstractHandler()
        {
            /* ------------------------------------------------------------ */
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException,
                    ServletException
            {
                response.setStatus(HttpStatus.OK_200);
                response.addHeader("For",request.getQueryString());
                response.addHeader("Name","Value");
                response.getOutputStream().print("blah");
                response.flushBuffer();
            }
        });

        server.start();

        port = server.getConnectors()[0].getLocalPort();

        client = new HttpClient();
        client.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        client.setConnectTimeout(5);
        client.start();

    }

    /* ------------------------------------------------------------ */
    @After
    public void tearDown() throws Exception
    {
        server.stop();
        client.stop();
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testHeaderWhenReadEarly() throws Exception
    {

        CachedExchange e1 = new CachedExchange(true);
        CachedExchange e2 = new CachedExchange(true);

        e1.setURL("http://localhost:" + port + "/?a=short");
        e2.setURL("http://localhost:" + port + "/?a=something_longer");

        client.send(e1);
        while (!e1.isDone())
            Thread.sleep(100);

        assertEquals("Read buffer","Value",e1.getResponseFields().getStringField("Name"));

        client.send(e2);
        while (!e2.isDone())
            Thread.sleep(100);

        assertEquals("Overwritten buffer","Value",e1.getResponseFields().getStringField("Name"));
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testHeaderWhenReadLate() throws Exception
    {

        CachedExchange e1 = new CachedExchange(true);
        CachedExchange e2 = new CachedExchange(true);

        e1.setURL("http://localhost:" + port + "/?a=short");
        e2.setURL("http://localhost:" + port + "/?a=something_longer");

        client.send(e1);
        while (!e1.isDone())
            Thread.sleep(100);

        client.send(e2);
        while (!e2.isDone())
            Thread.sleep(100);

        for ( Enumeration<String> e = e1.getResponseFields().getValues("Name"); e.hasMoreElements();)
        {
            System.out.println(e.nextElement());
        }
        
        assertEquals("Overwritten buffer","Value",e1.getResponseFields().getStringField("Name"));
    }
}