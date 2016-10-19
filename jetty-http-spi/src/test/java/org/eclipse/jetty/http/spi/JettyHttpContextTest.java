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

package org.eclipse.jetty.http.spi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.powermock.api.mockito.PowerMockito.mock;
import org.junit.Before;
import org.junit.Test;
import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class JettyHttpContextTest
{

    private HttpServer httpServer;

    private HttpHandler handler;

    private String path = "/test";

    private JettyHttpContext jettyHttpContext;

    private HttpHandler newHttpHandler;

    private Authenticator authenticator;

    @Before
    public void setUp() throws Exception
    {
        httpServer = mock(HttpServer.class);
        handler = mock(HttpHandler.class);
        jettyHttpContext = new JettyHttpContext(httpServer,path,handler);
    }

    @Test
    public void testBasicOperations()
    {
        assertNotNull("Contexthandler instance shouldn't be null",jettyHttpContext.getJettyContextHandler());
        assertEquals("Path should be equal",path,jettyHttpContext.getPath());
        assertEquals("Server instances must be equal",httpServer,jettyHttpContext.getServer());
        assertEquals("Handler instances must be equal",handler,jettyHttpContext.getHandler());
        assertNotNull("Attributes shouldn't be null",jettyHttpContext.getAttributes());
        assertNotNull("filters shouldn't be null",jettyHttpContext.getFilters());
    }

    @Test
    public void testGetSetHandler()
    {
        // given
        newHttpHandler = mock(HttpHandler.class);

        // when
        jettyHttpContext.setHandler(newHttpHandler);

        // then
        assertEquals("Handler instances must be equal",newHttpHandler,jettyHttpContext.getHandler());
    }

    @Test
    public void testGetSetAuthenticator()
    {
        // given
        authenticator = mock(Authenticator.class);

        // when
        jettyHttpContext.setAuthenticator(authenticator);

        // then
        assertEquals("Authenticator instances must be equal",authenticator,jettyHttpContext.getAuthenticator());
    }
}
