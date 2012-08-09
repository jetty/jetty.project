package org.eclipse.jetty.server;
//========================================================================
//Copyright (c) 1999-2009 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses.
//========================================================================

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

/**
 * Resource Handler test
 * <p/>
 * TODO: increase the testing going on here
 */
public class HttpFiveWaysToCommitTest
{
    private static Server server;
    private static SelectChannelConnector connector;


    @Before
    public void setUp() throws Exception
    {
        server = new Server();
        connector = new SelectChannelConnector(server);
        server.setConnectors(new Connector[]{connector});
    }

    /* ------------------------------------------------------------ */
    @After
    public void tearDown() throws Exception
    {
        server.stop();
    }

    @Test
    public void testHandlerSetsHandledTrueOnly() throws Exception
    {
        server.setHandler(new OnlySetHandledHandler());
        server.start();

        StringWriter writer = executeRequest(HttpStatus.OK_200);

        System.out.println("RESPONSE: " + writer.toString());
    }


    private class OnlySetHandledHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
        }
    }

    @Test
    public void testHandlerExplicitFlush() throws Exception
    {
        server.setHandler(new ExplicitFlushHandler());
        server.start();

        StringWriter writer = executeRequest(HttpStatus.OK_200);

        System.out.println("RESPONSE: " + writer.toString());
    }

    private class ExplicitFlushHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.getWriter().write("foobar");
            response.flushBuffer();
        }
    }

    @Test
    public void testHandlerDoesNotSetHandled() throws Exception
    {
        server.setHandler(new DoesNotSetHandledHandler());
        server.start();

        StringWriter writer = executeRequest(HttpStatus.NOT_FOUND_404);

        System.out.println("RESPONSE: " + writer.toString());
    }

    private class DoesNotSetHandledHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(false);
        }
    }

    @Test
    public void testCommitWithMoreDataToWrite() throws Exception
    {
        server.setHandler(new CommitResponseWithMoreDataToWriteHandler());
        server.start();

        StringWriter writer = executeRequest(HttpStatus.OK_200);

        System.out.println("RESPONSE: " + writer.toString());
    }

    private class CommitResponseWithMoreDataToWriteHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.getWriter().write("foo");
            response.flushBuffer();
            response.getWriter().write("bar");
        }
    }

    @Test
    public void testBufferOverflow() throws Exception
    {
        server.setHandler(new OverflowHandler());
        server.start();

        StringWriter writer = executeRequest(HttpStatus.OK_200);

        System.out.println("RESPONSE: " + writer.toString());
    }

    private class OverflowHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setContentLength(2);
            response.getWriter().write("foo");
        }
    }

    private StringWriter executeRequest(int expectedStatus) throws URISyntaxException, IOException
    {
        URI uri = new URI("http://localhost:" + connector.getLocalPort() + "/");
        HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();
        connection.connect();

        for (String header : connection.getHeaderFields().keySet())
        {
            System.out.println(header + ": " + connection.getHeaderFields().get(header));
        }
        int responseCode = connection.getResponseCode();
        assertThat("return code is 200 ok", responseCode, is(expectedStatus));

        StringWriter writer = new StringWriter();
        if (responseCode == HttpStatus.OK_200)
        {
            InputStream inputStream = connection.getInputStream();
            InputStreamReader reader = new InputStreamReader(inputStream);
            IO.copy(reader, writer);
        }

        return writer;
    }

}
