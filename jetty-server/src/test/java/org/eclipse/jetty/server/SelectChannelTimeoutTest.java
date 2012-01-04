// ========================================================================
// Copyright (c) 2010 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.server;

import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;

import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.IO;
import org.junit.Before;
import org.junit.Test;

public class SelectChannelTimeoutTest extends ConnectorTimeoutTest
{
    
    @Before
    public void init() throws Exception
    {
        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setMaxIdleTime(MAX_IDLE_TIME); // 250 msec max idle
        startServer(connector);
    }

    @Test(expected=SocketException.class)
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
         process(null);
    }
    
    @Test(expected=SocketException.class)
    public void testIdleTimeoutAfterTimeout() throws Exception
    {
        SuspendHandler _handler = new SuspendHandler();
        _server.stop();
        SessionHandler session = new SessionHandler();
        session.setHandler(_handler);
        _server.setHandler(session);
        _server.start();
        
        _handler.setSuspendFor(50);
        System.out.println(process(null));
    }
    
    @Test(expected=SocketException.class)
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
        System.out.println(process(null));
    }

    // TODO: remove code duplication to LocalAsyncContextTest.java
    private synchronized String process(String content) throws Exception
    {
        String request = "GET / HTTP/1.1\r\n" + "Host: localhost\r\n" + "Connection: close\r\n";

        if (content == null)
            request += "\r\n";
        else
            request += "Content-Length: " + content.length() + "\r\n" + "\r\n" + content;
        return getResponse(request);
    }

    protected String getResponse(String request) throws Exception
    {
        SelectChannelConnector connector = (SelectChannelConnector)_connector;
        Socket socket = new Socket((String)null,connector.getLocalPort());
        socket.getOutputStream().write(request.getBytes("UTF-8"));
        InputStream inputStream = socket.getInputStream();
        Thread.sleep(500);
        socket.getOutputStream().write(10);
        return IO.toString(inputStream);
    }

}
