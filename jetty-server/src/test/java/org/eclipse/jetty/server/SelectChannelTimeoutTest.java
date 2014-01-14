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

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.IO;
import org.junit.Before;
import org.junit.Test;

public class SelectChannelTimeoutTest extends ConnectorTimeoutTest
{
    @Before
    public void init() throws Exception
    {
        ServerConnector connector = new ServerConnector(_server,1,1);
        connector.setIdleTimeout(MAX_IDLE_TIME); // 250 msec max idle
        startServer(connector);
    }

    @Test
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

    @Test
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

    @Test
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
        Socket socket = new Socket((String)null,connector.getLocalPort());
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
