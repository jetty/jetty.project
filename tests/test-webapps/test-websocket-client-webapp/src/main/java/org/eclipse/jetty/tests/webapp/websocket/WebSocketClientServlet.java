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

package org.eclipse.jetty.tests.webapp.websocket;

import java.io.PrintWriter;
import java.net.URI;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.WebSocketClient;

@WebServlet("/")
public class WebSocketClientServlet extends HttpServlet
{
    private WebSocketClient client;

    @Override
    public void init() throws ServletException
    {
        // We can't use the jetty-websocket-httpclient.xml if the websocket client jars are in WEB-INF/lib.
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(true);
        HttpClient httpClient = new HttpClient(sslContextFactory);
        httpClient.setConnectTimeout(4999);
        this.client = new WebSocketClient(httpClient);

        try
        {
            this.client.start();
        }
        catch (Exception e)
        {
            throw new ServletException(e);
        }
    }

    @Override
    public void destroy()
    {
        try
        {
            client.stop();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
    {
        try
        {
            resp.setContentType("text/html");

            // Send and receive a websocket echo on the same server.
            ClientSocket clientSocket = new ClientSocket();
            URI wsUri = WSURI.toWebsocket(req.getRequestURL()).resolve("echo");
            client.connect(clientSocket, wsUri).get(5, TimeUnit.SECONDS);
            clientSocket.session.getRemote().sendString("test message");
            String response = clientSocket.textMessages.poll(5, TimeUnit.SECONDS);
            clientSocket.session.close();
            clientSocket.closeLatch.await(5, TimeUnit.SECONDS);

            PrintWriter writer = resp.getWriter();
            writer.println("WebSocketEcho: " + ("test message".equals(response) ? "success" : "failure"));
            writer.println("WebSocketEcho: success");
            writer.println("ConnectTimeout: " + client.getHttpClient().getConnectTimeout());
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @WebSocket
    public static class ClientSocket
    {
        public Session session;
        public CountDownLatch openLatch = new CountDownLatch(1);
        public CountDownLatch closeLatch = new CountDownLatch(1);
        public ArrayBlockingQueue<String> textMessages = new ArrayBlockingQueue<>(10);

        @OnWebSocketConnect
        public void onOpen(Session session)
        {
            this.session = session;
            openLatch.countDown();
        }

        @OnWebSocketMessage
        public void onMessage(String message)
        {
            textMessages.add(message);
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            closeLatch.countDown();
        }
    }
}
