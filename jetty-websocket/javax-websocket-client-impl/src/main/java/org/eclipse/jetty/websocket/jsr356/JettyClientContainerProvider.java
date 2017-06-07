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

import java.lang.reflect.Method;

import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.ShutdownThread;

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
    
    public Object getContextHandler()
    {
        try
        {
            // Equiv of: ContextHandler.Context context = ContextHandler.getCurrentContext()
            Class<?> clazzContextHandler = Class.forName("org.eclipse.jetty.server.handler.ContextHandler");
            Method methodGetContext = clazzContextHandler.getMethod("getCurrentContext");
            Object objContext = methodGetContext.invoke(null);
            if (objContext == null)
                return null;
            
            // Equiv of: ContextHandler handler = ContextHandler.getContextHandler(context);
            Class<?> clazzContext = objContext.getClass();
            Method methodGetContextHandler = clazzContextHandler.getMethod("getContextHandler", clazzContext);
            return methodGetContextHandler.invoke(objContext);
        }
        catch (Throwable ignore)
        {
            return null;
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
            try
            {
                Class<?> clazzServerContainer = Class.forName("org.eclipse.jetty.websocket.jsr356.server.ServerContainer");
                Method method = clazzServerContainer.getMethod("getWebSocketContainer");
                WebSocketContainer container = (WebSocketContainer) method.invoke(null);
                if (container != null)
                {
                    return container;
                }
            }
            catch (Throwable ignore)
            {
            }
            
            if (INSTANCE == null)
            {
                INSTANCE = new ClientContainer();
                
                Object contextHandler = getContextHandler();
                if (contextHandler != null && contextHandler instanceof ContainerLifeCycle)
                {
                    // Add as bean to contextHandler
                    // Allow startup to follow Jetty lifecycle
                    ((ContainerLifeCycle) contextHandler).addBean(INSTANCE, true);
                }
                else
                {
                    // Static Initialization
                    // register JVM wide shutdown thread
                    ShutdownThread.register(INSTANCE);
                    
                    if (!INSTANCE.isStarted())
                    {
                        try
                        {
                            INSTANCE.start();
                        }
                        catch (Exception e)
                        {
                            throw new RuntimeException("Unable to start Client Container", e);
                        }
                    }
                }
            }
            
            return INSTANCE;
        }
    }
}
