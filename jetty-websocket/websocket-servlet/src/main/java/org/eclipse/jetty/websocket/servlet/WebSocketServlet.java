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
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

/**
 * Abstract Servlet used to bridge the Servlet API to the WebSocket API.
 * <p>
 * To use this servlet, you will be required to register your websockets with the {@link WebSocketServletFactory} so that it can create your websockets under the
 * appropriate conditions.
 * <p>
 * The most basic implementation would be as follows.
 *
 * <pre>
 * package my.example;
 *
 * import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
 * import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
 *
 * public class MyEchoServlet extends WebSocketServlet
 * {
 *     &#064;Override
 *     public void configure(WebSocketServletFactory factory)
 *     {
 *         // set a 10 second idle timeout
 *         factory.getPolicy().setIdleTimeout(10000);
 *         // register my socket
 *         factory.register(MyEchoSocket.class);
 *     }
 * }
 * </pre>
 *
 * Note: that only request that conforms to a "WebSocket: Upgrade" handshake request will trigger the {@link WebSocketServletFactory} handling of creating
 * WebSockets.<br>
 * All other requests are treated as normal servlet requests.
 *
 * <p>
 * <b>Configuration / Init-Parameters:</b><br>
 * Note: If you use the {@link WebSocket &#064;WebSocket} annotation, these configuration settings can be specified on a per WebSocket basis, vs a per Servlet
 * basis.
 *
 * <dl>
 * <dt>maxIdleTime</dt>
 * <dd>set the time in ms that a websocket may be idle before closing<br>
 *
 * <dt>maxTextMessageSize</dt>
 * <dd>set the size in UTF-8 bytes that a websocket may be accept as a Text Message before closing<br>
 *
 * <dt>maxBinaryMessageSize</dt>
 * <dd>set the size in bytes that a websocket may be accept as a Binary Message before closing<br>
 *
 * <dt>inputBufferSize</dt>
 * <dd>set the size in bytes of the buffer used to read raw bytes from the network layer<br>
 * </dl>
 */
@SuppressWarnings("serial")
public abstract class WebSocketServlet extends HttpServlet
{
    private WebSocketServletFactory factory;

    public abstract void configure(WebSocketServletFactory factory);

    @Override
    public void destroy()
    {
        try
        {
            ServletContext ctx = getServletContext();
            ctx.removeAttribute(WebSocketServletFactory.class.getName());
            factory.stop();
            factory = null;
        }
        catch (Exception ignore)
        {
            // ignore;
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
            WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);

            String max = getInitParameter("maxIdleTime");
            if (max != null)
            {
                policy.setIdleTimeout(Long.parseLong(max));
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

            max = getInitParameter("inputBufferSize");
            if (max != null)
            {
                policy.setInputBufferSize(Integer.parseInt(max));
            }

            ServletContext ctx = getServletContext();
            factory = WebSocketServletFactory.Loader.load(ctx, policy);
            configure(factory);
            factory.start();
            ctx.setAttribute(WebSocketServletFactory.class.getName(), factory);
        }
        catch (Exception x)
        {
            throw new ServletException(x);
        }
    }

    /**
     * @see javax.servlet.http.HttpServlet#service(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        if (factory.isUpgradeRequest(request, response))
        {
            // We have an upgrade request
            if (factory.acceptWebSocket(request, response))
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
        super.service(request, response);
    }
}
