// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.server;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;

/**
 * Abstract Servlet used to bridge the Servlet API to the WebSocket API.
 * <p>
 * To use this servlet, you will be required to register your websockets with the {@link WebSocketServerFactory} so that it can create your websockets under the
 * appropriate conditions.
 * <p>
 * The most basic implementation would be as follows.
 * 
 * <pre>
 * package my.example;
 * 
 * import javax.servlet.http.HttpServletRequest;
 * import org.eclipse.jetty.websocket.WebSocket;
 * import org.eclipse.jetty.websocket.server.WebSocketServlet;
 * 
 * public class MyEchoServlet extends WebSocketServlet
 * {
 *     &#064;Override
 *     public void registerWebSockets(WebSocketServerFactory factory)
 *     {
 *         factory.register(MyEchoSocket.class);
 *     }
 * }
 * </pre>
 * 
 * Note: that only request that conforms to a "WebSocket: Upgrade" handshake request will trigger the {@link WebSocketServerFactory} handling of creating
 * WebSockets.<br>
 * All other requests are treated as normal servlet requests.
 * 
 * <p>
 * <b>Configuration / Init-Parameters:</b><br>
 * Note: If you use the {@link WebSocket &#064;WebSocket} annotation, these configuration settings can be specified on a per WebSocket basis, vs a per Servlet
 * basis.
 * 
 * <dl>
 * <dt>bufferSize</dt>
 * <dd>can be used to set the buffer size, which is also the max frame byte size<br>
 * <i>Default: 8192</i></dd>
 * 
 * <dt>maxIdleTime</dt>
 * <dd>set the time in ms that a websocket may be idle before closing<br>
 * <i>Default:</i></dd>
 * 
 * <dt>maxTextMessagesSize</dt>
 * <dd>set the size in characters that a websocket may be accept before closing<br>
 * <i>Default:</i></dd>
 * 
 * <dt>maxBinaryMessagesSize</dt>
 * <dd>set the size in bytes that a websocket may be accept before closing<br>
 * <i>Default:</i></dd>
 * </dl>
 */
@SuppressWarnings("serial")
public abstract class WebSocketServlet extends HttpServlet
{
    private final Logger LOG = Log.getLogger(getClass());
    private WebSocketServerFactory webSocketFactory;

    @Override
    public void destroy()
    {
        try
        {
            webSocketFactory.stop();
        }
        catch (Exception x)
        {
            LOG.ignore(x);
        }
    }

    /**
     * @see javax.servlet.GenericServlet#init()
     */
    @Override
    public void init() throws ServletException
    {
        try
        {
            String bs = getInitParameter("bufferSize");
            WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
            if (bs != null)
            {
                policy.setBufferSize(Integer.parseInt(bs));
            }

            String max = getInitParameter("maxIdleTime");
            if (max != null)
            {
                policy.setIdleTimeout(Integer.parseInt(max));
            }

            max = getInitParameter("maxTextMessageSize");
            if (max != null)
            {
                policy.setMaxTextMessageSize(Integer.parseInt(max));
            }

            max = getInitParameter("maxBinaryMessageSize");
            if (max != null)
            {
                policy.setMaxBinaryMessageSize(Integer.parseInt(max));
            }

            webSocketFactory = new WebSocketServerFactory(policy);

            registerWebSockets(webSocketFactory);

            webSocketFactory.start();
        }
        catch (Exception x)
        {
            throw new ServletException(x);
        }
    }

    public abstract void registerWebSockets(WebSocketServerFactory factory);

    /**
     * @see javax.servlet.http.HttpServlet#service(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        if (webSocketFactory.isUpgradeRequest(request,response))
        {
            // We have an upgrade request
            if (webSocketFactory.acceptWebSocket(request,response))
            {
                // We have a socket instance created
                return;
            }
            // If we reach this point, it means we had an incoming request to upgrade
            // but it was either not a proper websocket upgrade, or it was possibly rejected
            // due to incoming request constraints (controlled by WebSocketCreator)
            if (response.isCommitted())
            {
                // not much we can do at this point.
                return;
            }
        }

        // All other processing
        super.service(request,response);
    }
}
