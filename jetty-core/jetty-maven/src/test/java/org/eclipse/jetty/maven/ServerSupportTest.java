//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.maven;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerSupportTest
{
    @Test
    public void testNoServerHandlers() throws Exception
    {
        //Test that a server will always create a ContextHandlerCollection and DefaultHandler
        Server server = new Server();
        assertNull(server.getHandler());
        ServerSupport.configureHandlers(server, null, null);
        assertNotNull(server.getDefaultHandler());
        assertNotNull(server.getHandler());
    }

    @Test
    public void testExistingServerHandler() throws Exception
    {
        //Test that if a Server already has a handler, we replace it with a
        //sequence containing the original handler plus a ContextHandlerCollection
        Server server = new Server();
        Handler.Abstract testHandler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                return false;
            }
        };

        server.setHandler(testHandler);
        ServerSupport.configureHandlers(server, null, null);
        assertNotNull(server.getDefaultHandler());
        assertInstanceOf(Handler.Sequence.class, server.getHandler());
        Handler.Sequence handlers = (Handler.Sequence)server.getHandler();
        assertTrue(handlers.contains(testHandler));
        assertNotNull(handlers.getDescendant(ContextHandlerCollection.class));
    }

    @Test
    public void testExistingServerHandlerWithContextHandlers() throws Exception
    {
        //Test that if a Server already has a handler, we replace it with
        //a sequence containing the original handler plus a ContextHandlerCollection
        //into which we add any supplied ContextHandlers
        Server server = new Server();
        Handler.Abstract testHandlerA = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                return false;
            }
        };

        ContextHandler contextHandlerA = new ContextHandler();
        contextHandlerA.setContextPath("/A");
        ContextHandler contextHandlerB = new ContextHandler();
        contextHandlerB.setContextPath("/B");
        List<ContextHandler> contextHandlerList = Arrays.asList(contextHandlerA, contextHandlerB);

        server.setHandler(testHandlerA);
        ServerSupport.configureHandlers(server, contextHandlerList, null);
        assertNotNull(server.getDefaultHandler());
        assertInstanceOf(Handler.Sequence.class, server.getHandler());
        Handler.Sequence handlers = (Handler.Sequence)server.getHandler();
        List<Handler> handlerList = handlers.getHandlers();
        assertEquals(testHandlerA, handlerList.get(0));
        Handler second = handlerList.get(1);
        assertInstanceOf(ContextHandlerCollection.class, second);
        ContextHandlerCollection contextHandlers = (ContextHandlerCollection)second;
        Set<String> contextPaths = contextHandlers.getContextPaths();
        assertNotNull(contextPaths);
        assertTrue(contextPaths.contains("/A"));
        assertTrue(contextPaths.contains("/B"));
    }
}
