//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {

        WebSocketClient client = null;
        try
        {
            client = new WebSocketClient();
            WebSocketClient finalClient = client;
            runThrowExceptionsAsRuntime(() -> finalClient.start());
            resp.setContentType("text/html");
            //resp.getWriter().println("ConnectTimeout: " + client.getHttpClient().getConnectTimeout());

            ClientSocket clientSocket = new ClientSocket();
            URI wsUri = WSURI.toWebsocket(req.getRequestURL()).resolve("echo");
            client.connect(clientSocket, wsUri);
            clientSocket.openLatch.await(5, TimeUnit.SECONDS);
            clientSocket.session.getRemote().sendString("test message");
            String response = clientSocket.textMessages.poll(5, TimeUnit.SECONDS);
            if (!"test message".equals(response))
                throw new RuntimeException("incorrect response");
            clientSocket.session.close();
            clientSocket.closeLatch.await(5, TimeUnit.SECONDS);
            resp.getWriter().println("WebSocketEcho: success");
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        finally
        {
            if (client != null)
            {
                WebSocketClient finalClient = client;
                runThrowExceptionsAsRuntime(() -> finalClient.stop());
            }
        }
    }

    public interface ThrowingRunnable
    {
        void run() throws Exception;
    }

    public void runThrowExceptionsAsRuntime(ThrowingRunnable runnable)
    {
        try
        {
            runnable.run();
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
