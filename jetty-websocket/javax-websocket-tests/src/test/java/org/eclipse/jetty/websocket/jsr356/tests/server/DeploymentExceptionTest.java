//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.tests.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.websocket.DeploymentException;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.jsr356.server.JavaxWebSocketServerContainer;
import org.eclipse.jetty.websocket.jsr356.tests.server.sockets.InvalidCloseIntSocket;
import org.eclipse.jetty.websocket.jsr356.tests.server.sockets.InvalidErrorErrorSocket;
import org.eclipse.jetty.websocket.jsr356.tests.server.sockets.InvalidErrorIntSocket;
import org.eclipse.jetty.websocket.jsr356.tests.server.sockets.InvalidOpenCloseReasonSocket;
import org.eclipse.jetty.websocket.jsr356.tests.server.sockets.InvalidOpenIntSocket;
import org.eclipse.jetty.websocket.jsr356.tests.server.sockets.InvalidOpenSessionIntSocket;
import org.eclipse.jetty.websocket.servlet.NativeWebSocketConfiguration;
import org.eclipse.jetty.websocket.servlet.ServletContextWebSocketContainer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Deploy various {@link ServerEndpoint} annotated classes with invalid signatures,
 * check for {@link DeploymentException}
 */
@RunWith(Parameterized.class)
public class DeploymentExceptionTest
{
    @Parameters(name = "{0}")
    public static Collection<Class<?>[]> data()
    {
        List<Class<?>[]> data = new ArrayList<>();

        data.add(new Class<?>[]{InvalidCloseIntSocket.class});
        data.add(new Class<?>[]{InvalidErrorErrorSocket.class});
        // TODO: data.add(new Class<?>[]{InvalidErrorExceptionSocket.class});
        data.add(new Class<?>[]{InvalidErrorIntSocket.class});
        data.add(new Class<?>[]{InvalidOpenCloseReasonSocket.class});
        data.add(new Class<?>[]{InvalidOpenIntSocket.class});
        data.add(new Class<?>[]{InvalidOpenSessionIntSocket.class});

        // TODO: invalid return types
        // TODO: static methods
        // TODO: private or protected methods
        // TODO: abstract methods

        return data;
    }

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    /** The pojo to test */
    @Parameterized.Parameter(0)
    public Class<?> pojo;

    private Server server;
    private HandlerCollection contexts;

    @Before
    public void startServer() throws Exception
    {
        server = new Server(0);
        contexts = new HandlerCollection(true, new Handler[0]);
        server.setHandler(contexts);
        server.start();
    }

    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testDeploy_InvalidSignature() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();

        ServletContextWebSocketContainer contextContainer = ServletContextWebSocketContainer.get(context.getServletContext());
        NativeWebSocketConfiguration configuration = new NativeWebSocketConfiguration(context.getServletContext());
        HttpClient httpClient = new HttpClient();

        JavaxWebSocketServerContainer container = new JavaxWebSocketServerContainer(contextContainer, configuration, httpClient);
        context.addBean(container);

        contexts.addHandler(context);
        try
        {
            context.start();
            expectedException.expect(DeploymentException.class);
            container.addEndpoint(pojo);
        }
        finally
        {
            context.stop();
        }
    }
}
