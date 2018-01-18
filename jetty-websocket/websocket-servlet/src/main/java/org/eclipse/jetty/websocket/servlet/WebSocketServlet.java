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

package org.eclipse.jetty.websocket.servlet;

import java.io.IOException;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.websocket.core.server.Handshaker;
import org.eclipse.jetty.websocket.core.server.HandshakerFactory;

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
 * import WebSocketServlet;
 * import WebSocketServletFactory;
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
    private WebSocketServletNegotiator negotiator;

    public abstract void configure(WebSocketServletFactory factory);

    /**
     * @see javax.servlet.GenericServlet#init()
     */
    @Override
    public void init() throws ServletException
    {
        try
        {
            ServletContext ctx = getServletContext();

            ServletContextWebSocketContainer wsContainer = ServletContextWebSocketContainer.get(ctx);
            factory = new WebSocketServletFactory(wsContainer);
            String max = getInitParameter("maxIdleTime");
            if (max != null)
            {
                factory.getPolicy().setIdleTimeout(Long.parseLong(max));
            }

            max = getInitParameter("maxTextMessageSize");
            if (max != null)
            {
                factory.getPolicy().setMaxTextMessageSize(Integer.parseInt(max));
            }

            max = getInitParameter("maxBinaryMessageSize");
            if (max != null)
            {
                factory.getPolicy().setMaxBinaryMessageSize(Integer.parseInt(max));
            }

            max = getInitParameter("inputBufferSize");
            if (max != null)
            {
                factory.getPolicy().setInputBufferSize(Integer.parseInt(max));
            }

            configure(factory); // Let user modify factory, policy, creator, extensions, etc..
            negotiator = new WebSocketServletNegotiator(factory, factory.getCreator());
            
            ctx.setAttribute(WebSocketServletFactory.class.getName(),factory);
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
        Handshaker handshaker = HandshakerFactory.getHandshaker(request);

        // Attempt to upgrade
        if (handshaker != null && handshaker.upgradeRequest(negotiator, request, response))
        {
            // Upgrade was a success, nothing else to do.
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

        // All other processing
        super.service(request,response);
    }
}
