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

package org.eclipse.jetty.websocket.jsr356.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import javax.websocket.DeploymentException;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.jsr356.server.samples.InvalidCloseIntSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.InvalidErrorErrorSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.InvalidErrorExceptionSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.InvalidErrorIntSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.InvalidOpenCloseReasonSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.InvalidOpenIntSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.InvalidOpenSessionIntSocket;
import org.eclipse.jetty.websocket.server.MappedWebSocketCreator;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
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
        data.add(new Class<?>[]{InvalidErrorExceptionSocket.class});
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
    
    @Test
    public void testDeploy_InvalidSignature() throws Exception
    {
        MappedWebSocketCreator creator = new DummyCreator();
        WebSocketServerFactory serverFactory = new WebSocketServerFactory();
        Executor executor = new QueuedThreadPool();
        ServerContainer container = new ServerContainer(creator, serverFactory, executor);
        
        try
        {
            container.start();
            expectedException.expect(DeploymentException.class);
            container.addEndpoint(pojo);
        }
        finally
        {
            container.stop();
        }
    }
}
