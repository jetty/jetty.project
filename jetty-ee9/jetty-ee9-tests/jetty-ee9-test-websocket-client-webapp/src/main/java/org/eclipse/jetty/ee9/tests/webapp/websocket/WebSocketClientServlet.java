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

package org.eclipse.jetty.ee9.tests.webapp.websocket;

import java.io.PrintWriter;
import java.net.URI;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.ee9.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.ee9.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.ee9.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.ee9.websocket.api.util.WSURI;
import org.eclipse.jetty.ee9.websocket.client.WebSocketClient;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;

@WebServlet("/")
public class WebSocketClientServlet extends HttpServlet
{
    private WebSocketClient client;

    @Override
    public void init() throws ServletException
    {
        // We can't use the jetty-websocket-httpclient.xml if the websocket client jars are in WEB-INF/lib.
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(true);
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(sslContextFactory);
        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP(clientConnector));
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
