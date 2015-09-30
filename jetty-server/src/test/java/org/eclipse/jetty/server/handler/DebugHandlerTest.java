//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.handler;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.SimpleRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class DebugHandlerTest
{
    private Server server;
    private URI serverURI;
    @SuppressWarnings("deprecation")
    private DebugHandler debugHandler;
    private ByteArrayOutputStream capturedLog;
    
    @SuppressWarnings("deprecation")
    @Before
    public void startServer() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
        
        debugHandler = new DebugHandler();
        capturedLog = new ByteArrayOutputStream();
        debugHandler.setOutputStream(capturedLog);
        debugHandler.setHandler(new AbstractHandler()
            {
                @Override
                public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
                {
                    baseRequest.setHandled(true);
                    response.setStatus(HttpStatus.OK_200);
                }
            });
        server.setHandler(debugHandler);
        server.start();
        
        serverURI = URI.create(String.format("http://%s:%d/", connector.getHost()==null?"localhost":connector.getHost(), connector.getLocalPort()));
    }
    
    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Test
    public void testThreadName() throws IOException
    {
        SimpleRequest req = new SimpleRequest(serverURI);
        req.getString("/foo/bar?a=b");
        
        String log = capturedLog.toString(StandardCharsets.UTF_8.name());
        String expectedThreadName = String.format("http://%s:%s/foo/bar?a=b",serverURI.getHost(),serverURI.getPort());
        assertThat("ThreadName", log, containsString(expectedThreadName));
    }
}
