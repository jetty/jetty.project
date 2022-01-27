//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.jakarta.client;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.jakarta.client.internal.JakartaWebSocketClientContainer;

/**
 * Client {@link ContainerProvider} implementation.
 * <p>
 * Created by a {@link java.util.ServiceLoader} call in the
 * {@link jakarta.websocket.ContainerProvider#getWebSocketContainer()} call.
 * </p>
 */
public class JakartaWebSocketClientContainerProvider extends ContainerProvider
{
    public static void stop(WebSocketContainer container) throws Exception
    {
        if (container instanceof LifeCycle)
        {
            ((LifeCycle)container).stop();
        }
    }

    /**
     * Used by {@link ContainerProvider#getWebSocketContainer()} to get a <b>NEW INSTANCE</b>
     * of the Client {@link WebSocketContainer}.
     * <p>
     * <em>NOTE: A WebSocket Client Container is a heavyweight object.</em>
     * It is dangerous to repeatedly request a new container, or to manage many containers.
     * The existing jakarta.websocket API has no lifecycle for a ClientContainer, once started
     * they exist for the duration of the JVM with no ability to stop them.
     * See/Comment on <a href="https://github.com/javaee/websocket-spec/issues/212">jakarta.websocket Issue #212</a>
     * if this is a big concern for you.
     * </p>
     */
    @Override
    protected WebSocketContainer getContainer()
    {
        return getContainer(null);
    }

    /**
     * Get a new instance of a client {@link WebSocketContainer} which uses a supplied {@link HttpClient}.
     * @param httpClient a pre-configured {@link HttpClient} to be used by the implementation.
     * @see #getContainer()
     */
    public static WebSocketContainer getContainer(HttpClient httpClient)
    {
        JakartaWebSocketClientContainer clientContainer = new JakartaWebSocketClientContainer(httpClient);
        // See: https://github.com/eclipse-ee4j/websocket-api/issues/212
        LifeCycle.start(clientContainer);
        return clientContainer;
    }
}
