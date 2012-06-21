/*******************************************************************************
 * Copyright (c) 2011 Mort Bay Consulting Pty. Ltd.
 * ======================================================================
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *   The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 *
 *   The Apache License v2.0 is available at
 *   http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 *******************************************************************************/

package org.eclipse.jetty.websocket.server;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;

/**
 * Servlet to upgrade connections to WebSocket
 * <p/>
 * The request must have the correct upgrade headers, else it is
 * handled as a normal servlet request.
 * <p/>
 * The initParameter "bufferSize" can be used to set the buffer size,
 * which is also the max frame byte size (default 8192).
 * <p/>
 * The initParameter "maxIdleTime" can be used to set the time in ms
 * that a websocket may be idle before closing.
 * <p/>
 * The initParameter "maxTextMessagesSize" can be used to set the size in characters
 * that a websocket may be accept before closing.
 * <p/>
 * The initParameter "maxBinaryMessagesSize" can be used to set the size in bytes
 * that a websocket may be accept before closing.
 */
@SuppressWarnings("serial")
public abstract class WebSocketServlet extends HttpServlet implements WebSocketServerFactory.Acceptor
{
    private final Logger LOG = Log.getLogger(getClass());
    private WebSocketServerFactory webSocketFactory;

    @Override
    public boolean checkOrigin(HttpServletRequest request, String origin)
    {
        return true;
    }

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
            if(bs != null) {
                policy.setBufferSize(Integer.parseInt(bs));
            }

            String max = getInitParameter("maxIdleTime");
            if (max != null) {
                policy.setMaxIdleTime(Integer.parseInt(max));
            }

            max = getInitParameter("maxTextMessageSize");
            if (max != null) {
                policy.setMaxTextMessageSize(Integer.parseInt(max));
            }

            max = getInitParameter("maxBinaryMessageSize");
            if (max != null) {
                policy.setMaxBinaryMessageSize(Integer.parseInt(max));
            }

            webSocketFactory = new WebSocketServerFactory(this,policy);
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
        if (webSocketFactory.acceptWebSocket(request,response) || response.isCommitted())
        {
            return;
        }
        super.service(request, response);
    }
}
