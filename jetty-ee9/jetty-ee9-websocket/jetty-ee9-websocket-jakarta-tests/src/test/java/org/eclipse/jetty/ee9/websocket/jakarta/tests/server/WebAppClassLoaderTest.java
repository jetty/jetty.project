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

package org.eclipse.jetty.ee9.websocket.jakarta.tests.server;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.ee9.websocket.jakarta.common.JakartaWebSocketSession;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.EventSocket;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.WSServer;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Disabled
public class WebAppClassLoaderTest
{
    @ServerEndpoint("/echo")
    public static class MySocket
    {
        public static final CountDownLatch closeLatch = new CountDownLatch(1);
        public static final Map<String, ClassLoader> classLoaders = new ConcurrentHashMap<>();

        public MySocket()
        {
            classLoaders.put("constructor", Thread.currentThread().getContextClassLoader());
        }

        @OnOpen
        public void onOpen(Session session)
        {
            classLoaders.put("onOpen", Thread.currentThread().getContextClassLoader());
        }

        @OnMessage
        public void onMessage(Session session, String msg)
        {
            classLoaders.put("onMessage", Thread.currentThread().getContextClassLoader());
        }

        @OnError
        public void onError(Throwable error)
        {
            classLoaders.put("onError", Thread.currentThread().getContextClassLoader());
        }

        @OnClose
        public void onClose(CloseReason closeReason)
        {
            classLoaders.put("onClose", Thread.currentThread().getContextClassLoader());
            closeLatch.countDown();
        }
    }

    private WSServer server;
    private WebAppContext webapp;

    @BeforeEach
    public void startServer() throws Exception
    {
        Path testdir = MavenTestingUtils.getTargetTestingPath(WebAppClassLoaderTest.class.getName());
        server = new WSServer(testdir);
        WSServer.WebApp app = server.createWebApp("app");
        app.createWebInf();
        app.copyClass(MySocket.class);
        app.deploy();
        webapp = app.getWebAppContext();
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    private void awaitServerClose() throws Exception
    {
        ClassLoader webAppClassLoader = webapp.getClassLoader();
        Class<?> mySocketClass = webAppClassLoader.loadClass(MySocket.class.getName());
        CountDownLatch closeLatch = (CountDownLatch)mySocketClass.getDeclaredField("closeLatch").get(null);
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    private ClassLoader getClassLoader(String event) throws Exception
    {
        ClassLoader webAppClassLoader = webapp.getClassLoader();
        Class<?> mySocketClass = webAppClassLoader.loadClass(MySocket.class.getName());
        Map<String, ClassLoader> classLoaderMap = (Map)mySocketClass.getDeclaredField("classLoaders").get(null);
        return classLoaderMap.get(event);
    }

    @ParameterizedTest
    @ValueSource(strings = {"constructor", "onOpen", "onMessage", "onError", "onClose"})
    public void testForWebAppClassLoader(String event) throws Exception
    {
        WebSocketContainer client = ContainerProvider.getWebSocketContainer();
        EventSocket clientSocket = new EventSocket();
        Session session = client.connectToServer(clientSocket, server.getWsUri().resolve("/app/echo"));
        session.getBasicRemote().sendText("trigger onMessage -> onError -> onClose");
        ((JakartaWebSocketSession)session).abort();
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        awaitServerClose();

        ClassLoader webAppClassLoader = webapp.getClassLoader();
        assertThat(event, getClassLoader(event), is(webAppClassLoader));
    }
}
