//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertTrue;

public class ServerConnectorTimeoutTest extends ConnectorTimeoutTest
{
    @Before
    public void init() throws Exception
    {
        ServerConnector connector = new ServerConnector(_server,1,1);
        connector.setIdleTimeout(MAX_IDLE_TIME);
        startServer(connector);
    }

    @Test(timeout=60000)
    public void testIdleTimeoutAfterSuspend() throws Exception
    {
        SuspendHandler _handler = new SuspendHandler();
        _server.stop();
        SessionHandler session = new SessionHandler();
        session.setHandler(_handler);
        _server.setHandler(session);
        _server.start();

        _handler.setSuspendFor(100);
        _handler.setResumeAfter(25);
        assertTrue(process(null).toUpperCase(Locale.ENGLISH).contains("RESUMED"));
    }

    @Test(timeout=60000)
    public void testIdleTimeoutAfterTimeout() throws Exception
    {
        SuspendHandler _handler = new SuspendHandler();
        _server.stop();
        SessionHandler session = new SessionHandler();
        session.setHandler(_handler);
        _server.setHandler(session);
        _server.start();

        _handler.setSuspendFor(50);
        assertTrue(process(null).toUpperCase(Locale.ENGLISH).contains("TIMEOUT"));
    }

    @Test(timeout=60000)
    public void testIdleTimeoutAfterComplete() throws Exception
    {
        SuspendHandler _handler = new SuspendHandler();
        _server.stop();
        SessionHandler session = new SessionHandler();
        session.setHandler(_handler);
        _server.setHandler(session);
        _server.start();

        _handler.setSuspendFor(100);
        _handler.setCompleteAfter(25);
        assertTrue(process(null).toUpperCase(Locale.ENGLISH).contains("COMPLETED"));
    }

    private synchronized String process(String content) throws UnsupportedEncodingException, IOException, InterruptedException
    {
        String request = "GET / HTTP/1.1\r\n" + "Host: localhost\r\n";

        if (content == null)
            request += "\r\n";
        else
            request += "Content-Length: " + content.length() + "\r\n" + "\r\n" + content;
        return getResponse(request);
    }

    private String getResponse(String request) throws UnsupportedEncodingException, IOException, InterruptedException
    {
        ServerConnector connector = (ServerConnector)_connector;

        try (Socket socket = new Socket((String)null,connector.getLocalPort()))
        {
            socket.setSoTimeout(10 * MAX_IDLE_TIME);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            InputStream inputStream = socket.getInputStream();
            long start = System.currentTimeMillis();
            String response = IO.toString(inputStream);
            long timeElapsed = System.currentTimeMillis() - start;
            assertTrue("Time elapsed should be at least MAX_IDLE_TIME",timeElapsed > MAX_IDLE_TIME);
            return response;
        }
    }

    @Test
    public void testHttpWriteIdleTimeout() throws Exception
    {
        _httpConfiguration.setBlockingTimeout(500);
        configureServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });
        Socket client=newSocket(_serverURI.getHost(),_serverURI.getPort());
        client.setSoTimeout(10000);

        Assert.assertFalse(client.isClosed());

        OutputStream os=client.getOutputStream();
        InputStream is=client.getInputStream();

        try (StacklessLogging scope = new StacklessLogging(HttpChannel.class))
        {
            os.write((
                    "POST /echo HTTP/1.0\r\n"+
                            "host: "+_serverURI.getHost()+":"+_serverURI.getPort()+"\r\n"+
                            "content-type: text/plain; charset=utf-8\r\n"+
                            "content-length: 20\r\n"+
                            "\r\n").getBytes("utf-8"));
            os.flush();

            os.write("123456789\n".getBytes("utf-8"));
            os.flush();
            Thread.sleep(1000);
            os.write("=========\n".getBytes("utf-8"));
            os.flush();

            Thread.sleep(2000);

            String response =IO.toString(is);
            Assert.assertThat(response,containsString(" 500 "));
            Assert.assertThat(response, Matchers.not(containsString("=========")));
        }
    }
}
