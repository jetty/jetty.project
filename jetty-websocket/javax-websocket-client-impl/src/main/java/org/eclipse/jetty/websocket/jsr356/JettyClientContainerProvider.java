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

package org.eclipse.jetty.websocket.jsr356;

import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.scopes.SimpleContainerScope;

/**
 * Client {@link ContainerProvider} implementation.
 * <p>
 * Created by a {@link java.util.ServiceLoader} call in the
 * {@link javax.websocket.ContainerProvider#getWebSocketContainer()} call.
 */
public class JettyClientContainerProvider extends ContainerProvider
{
    private static Object lock = new Object();
    private static ClientContainer INSTANCE;
    
    public static ClientContainer getInstance()
    {
        return INSTANCE;
    }
    
    public static void stop() throws Exception
    {
        synchronized (lock)
        {
            if (INSTANCE == null)
            {
                return;
            }
            
            try
            {
                INSTANCE.stop();
            }
            finally
            {
                INSTANCE = null;
            }
        }
    }
    
    /**
     * Used by {@link ContainerProvider#getWebSocketContainer()} to get a new instance
     * of the Client {@link WebSocketContainer}.
     */
    @Override
    protected WebSocketContainer getContainer()
    {
        synchronized (lock)
        {
            if (INSTANCE == null)
            {
                SimpleContainerScope containerScope = new SimpleContainerScope(WebSocketPolicy.newClientPolicy());
                QueuedThreadPool threadPool= new QueuedThreadPool();
                String name = "Jsr356Client@" + hashCode();
                threadPool.setName(name);
                threadPool.setDaemon(true);
                containerScope.setExecutor(threadPool);
                containerScope.addBean(threadPool);
                INSTANCE = new ClientContainer(containerScope);
            }
        
            if (!INSTANCE.isStarted())
            {
                try
                {
                    // We need to start this container properly.
                    INSTANCE.start();
                }
                catch (Exception e)
                {
                    throw new RuntimeException("Unable to start Client Container", e);
                }
            }
        
            return INSTANCE;
        }
    }
}
