//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import static org.junit.Assert.fail;
import org.eclipse.jetty.http.spi.util.SpiConstants;
import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import com.sun.net.httpserver.HttpContext;

public class JettyHttpServerCreateContextTest extends JettyHttpServerCreateContextBase
{

    private HttpContext context;

    private Server server;

    @Before
    public void setUp() throws Exception
    {
        initializeJettyHttpServer(new Server());
    }

    @After
    public void tearDown() throws Exception
    {
        if (jettyHttpServer != null)
        {
            jettyHttpServer.stop(SpiConstants.ONE);
        }
    }

    @Test(expected = RuntimeException.class)
    public void testWithoutContextHandler()
    {
        // when
        jettyHttpServer.createContext("/");

        // then
        fail("A runtime exception must have occured by now as We haven't configure at " + "least one context handler collection.");
    }

    @Test(expected = RuntimeException.class)
    public void testWithContextHandler()
    {
        // given
        server = getContextHandlerServer();
        initializeJettyHttpServer(server);

        // when
        jettyHttpServer.createContext("/");

        // then
        fail("A runtime exception must have occured by now as another context already bound to the given path.");
    }

    @Test(expected = RuntimeException.class)
    public void testWithoutMatchedContextHandler()
    {
        // given
        server = getContextHandlerServer();
        initializeJettyHttpServer(server);

        // when
        jettyHttpServer.createContext("/a-context-that-does-not-exist");

        // then
        fail("A runtime exception must have occured by now as there is no matching " + "context handler collection has found.");
    }

    @Test
    public void testWithMatchedContextHandler()
    {
        // given
        server = getContextHandlerCollectionServer();
        initializeJettyHttpServer(server);

        // when
        context = jettyHttpServer.createContext("/");

        // then
        assertEquals("Path must be equal to /","/",context.getPath());
    }

    @Test
    public void testWithMatchedContextHandlerCollections()
    {
        // given
        server = getContextHandlerCollectionsServer();
        initializeJettyHttpServer(server);

        // when
        context = jettyHttpServer.createContext("/");

        // then
        assertEquals("Path must be equal to /","/",context.getPath());
    }
}
