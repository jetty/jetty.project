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

package org.eclipse.jetty.websocket.jsr356.server;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.ClientEndpoint;
import javax.websocket.DeploymentException;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerContainer;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class LargeServerContainerAsClientContainerServlet extends HttpServlet
{
    private static final Logger LOG = Log.getLogger(LargeServerContainerAsClientContainerServlet.class);
    private static final int LARGER_THAN_DEFAULT_SIZE;
    private WebSocketContainer clientContainer;

    static
    {
        WebSocketPolicy defaultPolicy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        LARGER_THAN_DEFAULT_SIZE = defaultPolicy.getMaxTextMessageSize() * 3;
    }

    @Override
    public void init() throws ServletException
    {
        super.init();

        ServerContainer serverContainer = (ServerContainer)getServletContext().getAttribute(ServerContainer.class.getName());
        clientContainer = serverContainer;
    }

    @SuppressWarnings("Duplicates")
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        int size = LARGER_THAN_DEFAULT_SIZE;

        String sizeParam = req.getParameter("size");
        if (StringUtil.isNotBlank(sizeParam))
        {
            size = Integer.parseInt(sizeParam);
        }

        byte[] buf = new byte[size];
        Arrays.fill(buf, (byte)'x');

        String destUrl = req.getParameter("destUrl");
        if (StringUtil.isBlank(destUrl))
        {
            resp.sendError(HttpServletResponse.SC_EXPECTATION_FAILED, "Missing destUrl");
            return;
        }

        URI wsUri = URI.create(destUrl);

        try
        {
            Session session = clientContainer.connectToServer(EchoClientSocket.class, wsUri);
            EchoClientSocket clientSocket = (EchoClientSocket)session.getUserProperties().get("endpoint");
            String message = new String(buf, UTF_8);
            session.getBasicRemote().sendText(message);
            String echoed = clientSocket.messages.poll(1, TimeUnit.SECONDS);
            assertThat("Echoed", echoed, is(message));
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().println("Success");
        }
        catch (DeploymentException e)
        {
            LOG.warn("Unable to deploy client socket", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Deployment error");
        }
        catch (InterruptedException e)
        {
            LOG.warn("Unable to find echoed message", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Echoed message missing?");
        }
    }

    @ClientEndpoint
    public static class EchoClientSocket
    {
        public BlockingArrayQueue<String> messages = new BlockingArrayQueue<>();

        @OnOpen
        public void onOpen(Session session)
        {
            session.getUserProperties().put("endpoint", this);
        }

        @OnMessage
        public void onMessage(String msg)
        {
            messages.offer(msg);
        }
    }
}
