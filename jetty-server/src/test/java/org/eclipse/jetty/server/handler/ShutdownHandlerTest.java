package org.eclipse.jetty.server.handler;

//========================================================================
//Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Server;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class ShutdownHandlerTest
{
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;

    private Server server  = new Server(0);
    private String shutdownToken = "asdlnsldgnklns";

    // class under test
    private ShutdownHandler shutdownHandler;

    @Before
    public void startServer() throws Exception
    {
        MockitoAnnotations.initMocks(this);
        server.start();
        shutdownHandler = new ShutdownHandler(server,shutdownToken);
    }

    @Test
    public void shutdownServerWithCorrectTokenAndIPTest() throws Exception
    {
        setDefaultExpectations();
        shutdownHandler.handle("/shutdown",null,request,response);
        assertEquals("Server should be stopped","STOPPED",server.getState());
    }

    @Test
    public void wrongTokenTest() throws Exception
    {
        setDefaultExpectations();
        when(request.getParameter("token")).thenReturn("anothertoken");
        shutdownHandler.handle("/shutdown",null,request,response);
        assertEquals("Server should be running","STARTED",server.getState());
    }

     @Test
     public void shutdownRequestNotFromLocalhostTest() throws Exception
     {
         setDefaultExpectations();
         when(request.getRemoteAddr()).thenReturn("192.168.3.3");
         shutdownHandler.handle("/shutdown",null,request,response);
         assertEquals("Server should be running","STARTED",server.getState());
     }

     private void setDefaultExpectations()
     {
         when(request.getMethod()).thenReturn("POST");
         when(request.getParameter("token")).thenReturn(shutdownToken);
         when(request.getRemoteAddr()).thenReturn("127.0.0.1");
     }

}
