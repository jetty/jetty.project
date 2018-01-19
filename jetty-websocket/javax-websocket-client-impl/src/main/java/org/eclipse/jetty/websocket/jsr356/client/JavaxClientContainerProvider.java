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

package org.eclipse.jetty.websocket.jsr356.client;

import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.ShutdownThread;

/**
 * Client {@link ContainerProvider} implementation.
 * <p>
 * Created by a {@link java.util.ServiceLoader} call in the
 * {@link javax.websocket.ContainerProvider#getWebSocketContainer()} call.
 * </p>
 */
public class JavaxClientContainerProvider extends ContainerProvider
{
    public static void stop(WebSocketContainer container) throws Exception
    {
        if (container instanceof LifeCycle)
        {
            ((LifeCycle) container).stop();
        }
    }

    /**
     * Used by {@link ContainerProvider#getWebSocketContainer()} to get a <b>NEW INSTANCE</b>
     * of the Client {@link WebSocketContainer}.
     * <p>
     *     <em>NOTE: A WebSocket Client Container is a heavyweight object.</em>
     *     It is dangerous to repeatedly request a new container, or to manage many containers.
     *     The existing javax.websocket API has no lifecycle for a ClientContainer, once started
     *     they exist for the duration of the JVM with no ability to stop them.
     *     See/Comment on <a href="https://github.com/javaee/websocket-spec/issues/212">javax.websocket Issue #212</a>
     *     if this is a big concern for you.
     * </p>
     */
    @Override
    protected WebSocketContainer getContainer()
    {
        // See: https://github.com/javaee/websocket-spec/issues/212
        // TODO: on multiple executions, do we warn?
        // TODO: do we care?
        // TODO: on multiple executions, do we share bufferPool/executors/etc?
        // TODO: do we want to provide a non-standard way to configure to always return the same clientContainer based on a config somewhere? (system.property?)

        JavaxClientContainer clientContainer = new JavaxClientContainer();

        // Register as JVM runtime shutdown hook?
        ShutdownThread.register(clientContainer);

        if (!clientContainer.isStarted())
        {
            try
            {
                clientContainer.start();
            }
            catch (Exception e)
            {
                throw new RuntimeException("Unable to start Client Container", e);
            }
        }

        return clientContainer;
    }
}
