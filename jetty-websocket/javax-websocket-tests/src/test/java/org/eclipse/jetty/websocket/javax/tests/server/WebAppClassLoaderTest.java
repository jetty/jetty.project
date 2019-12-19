//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.javax.tests.server;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.javax.tests.EventSocket;
import org.eclipse.jetty.websocket.javax.tests.WSServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebAppClassLoaderTest
{
    @ServerEndpoint("/echo")
    public static class MySocket
    {
        private final static CompletableFuture<ClassLoader> constructorClassLoader = new CompletableFuture<>();
        private final static CompletableFuture<ClassLoader> onOpenClassLoader = new CompletableFuture<>();
        private final static CompletableFuture<ClassLoader> onMessageClassLoader = new CompletableFuture<>();
        private final static CompletableFuture<ClassLoader> onErrorClassLoader = new CompletableFuture<>();
        private final static CompletableFuture<ClassLoader> onCloseClassLoader = new CompletableFuture<>();

        public MySocket()
        {
            constructorClassLoader.complete(Thread.currentThread().getContextClassLoader());
        }

        @OnOpen
        public void onOpen(Session session)
        {
            onOpenClassLoader.complete(Thread.currentThread().getContextClassLoader());
        }

        @OnMessage
        public void onMessage(Session session, String msg)
        {
            onMessageClassLoader.complete(Thread.currentThread().getContextClassLoader());
        }

        @OnError
        public void onError(Throwable error)
        {
            onErrorClassLoader.complete(Thread.currentThread().getContextClassLoader());
        }

        @OnClose
        public void onClose(CloseReason closeReason)
        {
            onCloseClassLoader.complete(Thread.currentThread().getContextClassLoader());
        }
    }

    private static WSServer server;
    private static WebAppContext webapp;

    @BeforeAll
    public static void startServer() throws Exception
    {
        Path testdir = MavenTestingUtils.getTargetTestingPath(WebAppClassLoaderTest.class.getName());
        server = new WSServer(testdir, "app");
        server.createWebInf();
        server.copyEndpoint(MySocket.class);
        server.start();
        webapp = server.createWebAppContext();
        server.deployWebapp(webapp);
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    private ClassLoader getClassLoader(String event) throws Exception
    {
        ClassLoader webAppClassLoader = webapp.getClassLoader();
        Class<?> aClass = webAppClassLoader.loadClass(MySocket.class.getName());
        Field field = aClass.getDeclaredField(event + "ClassLoader");
        field.setAccessible(true);
        return ((CompletableFuture<ClassLoader>)field.get(null)).get(5, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"constructor", "onOpen", "onMessage", "onError", "onClose"})
    public void test(String event) throws Exception
    {
        WebSocketContainer client = ContainerProvider.getWebSocketContainer();
        EventSocket clientSocket = new EventSocket();
        Session session = client.connectToServer(clientSocket, server.getWsUri().resolve("/app/echo"));
        session.getBasicRemote().sendText("trigger onMessage -> onError -> onClose");
        session.close();
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));

        ClassLoader webAppClassLoader = webapp.getClassLoader();
        assertThat(event, getClassLoader(event), is(webAppClassLoader));
    }
}
