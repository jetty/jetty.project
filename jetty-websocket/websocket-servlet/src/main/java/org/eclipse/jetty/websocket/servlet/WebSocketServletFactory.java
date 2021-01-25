//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.servlet;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Set;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;

/**
 * Basic WebSocketServletFactory for working with Jetty-based WebSocketServlets
 */
public interface WebSocketServletFactory
{
    class Loader
    {
        static final String DEFAULT_IMPL = "org.eclipse.jetty.websocket.server.WebSocketServerFactory";

        public static WebSocketServletFactory load(ServletContext ctx, WebSocketPolicy policy)
        {
            try
            {
                @SuppressWarnings("unchecked")
                Class<? extends WebSocketServletFactory> wsClazz =
                    (Class<? extends WebSocketServletFactory>)Class.forName(DEFAULT_IMPL, true, Thread.currentThread().getContextClassLoader());
                Constructor<? extends WebSocketServletFactory> ctor = wsClazz.getDeclaredConstructor(ServletContext.class, WebSocketPolicy.class);
                return ctor.newInstance(ctx, policy);
            }
            catch (ClassNotFoundException e)
            {
                throw new RuntimeException("Unable to load " + DEFAULT_IMPL, e);
            }
            catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e)
            {
                throw new RuntimeException("Unable to instantiate " + DEFAULT_IMPL, e);
            }
        }
    }

    boolean acceptWebSocket(HttpServletRequest request, HttpServletResponse response) throws IOException;

    boolean acceptWebSocket(WebSocketCreator creator, HttpServletRequest request, HttpServletResponse response) throws IOException;

    void start() throws Exception;

    void stop() throws Exception;

    /**
     * Get the set of available Extensions by registered name.
     *
     * @return the set of available extensions by registered name.
     */
    Set<String> getAvailableExtensionNames();

    WebSocketCreator getCreator();

    /**
     * Get the registered extensions for this WebSocket factory.
     *
     * @return the ExtensionFactory
     * @see #getAvailableExtensionNames()
     * @deprecated this class is removed from Jetty 10.0.0+.  To remove specific extensions
     * from negotiation use {@link WebSocketCreator} to remove then during handshake.
     */
    @Deprecated
    ExtensionFactory getExtensionFactory();

    /**
     * Get the base policy in use for WebSockets.
     * <p>
     * Note: individual WebSocket implementations can override some of the values in here by using the {@link WebSocket &#064;WebSocket} annotation.
     *
     * @return the base policy
     */
    WebSocketPolicy getPolicy();

    boolean isUpgradeRequest(HttpServletRequest request, HttpServletResponse response);

    /**
     * Register a websocket class pojo with the default {@link WebSocketCreator}.
     * <p>
     * Note: only required if using the default {@link WebSocketCreator} provided by this factory.
     *
     * @param websocketPojo the class to instantiate for each incoming websocket upgrade request.
     */
    void register(Class<?> websocketPojo);

    void setCreator(WebSocketCreator creator);
}
