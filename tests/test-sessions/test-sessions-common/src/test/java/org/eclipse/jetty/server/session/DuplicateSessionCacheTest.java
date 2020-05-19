//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server.session;

import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DuplicateSessionCacheTest
{
    private Server server;
    private HttpClient client;

    ServletContextHandler contextHandler;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        // Default session behavior
        contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        contextHandler.addServlet(DefaultServlet.class, "/");

        HandlerList handlers = new HandlerList();
        handlers.addHandler(contextHandler);
        handlers.addHandler(new DefaultHandler());

        server.setHandler(handlers);
        server.start();
    }

    @AfterEach
    public void stopServerAndClient()
    {
        LifeCycle.stop(server);
        LifeCycle.stop(client);
    }

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new HttpClient();
        client.start();
    }

    @Test
    public void testNewSessionUseExistingTrue() throws Exception
    {

        //Create a new session and get the Id
        Request request1 = new Request(null, null);
        HttpSession session1 = contextHandler.getSessionHandler().newHttpSession(request1);
        String id =  session1.getId();

        //Re-use the same session id and try and create a new session
        //With version 9.4.20 this would work but return a new session which is not ideal if one exists
        //After version 9.4.21 this would throw an exception
        //With the commit this test is included in now the existing session will be returned
        Request request2 = new Request(null, null);
        request2.setRequestedSessionId(id);
        HttpSession session2 = contextHandler.getSessionHandler().newHttpSession(request2,true);

        //Verify the two returned sessions are the same
        assertTrue(session1 == session2);
        assertEquals(id, session1.getId());
    }

    @Test
    public void testUseExistingCachedFalse() throws Exception
    {
        //Create a new session and get the Id
        Request request1 = new Request(null, null);
        HttpSession session1 = contextHandler.getSessionHandler().newHttpSession(request1);
        String id =  session1.getId();

        //Should return null because a session exists and useExisting is false
        Request request2 = new Request(null, null);
        request2.setRequestedSessionId(id);
        assertNull(contextHandler.getSessionHandler().newHttpSession(request2, false));
    }

    @Test
    public void testNewHttpSessionOriginalMethod() throws Exception
    {
        //Create a new session and get the Id
        Request request1 = new Request(null, null);
        HttpSession session1 = contextHandler.getSessionHandler().newHttpSession(request1);
        String id =  session1.getId();

        //Should return null because a session exists
        Request request2 = new Request(null, null);
        request2.setRequestedSessionId(id);
        assertNull(contextHandler.getSessionHandler().newHttpSession(request2));
    }
}
