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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import javax.websocket.CloseReason;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.javax.tests.Fuzzer;
import org.eclipse.jetty.websocket.javax.tests.LocalServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests various ways to echo with JSR356
 */
public class JsrEchoTest
{
    @SuppressWarnings("unused")
    @ServerEndpoint("/echo/basic")
    public static class EchoBasicTextSocket
    {
        private Session session;

        @OnOpen
        public void onOpen(Session session)
        {
            this.session = session;
        }

        @OnMessage
        public void onText(String msg)
        {
            try
            {
                session.getBasicRemote().sendText(msg);
            }
            catch (IOException esend)
            {
                esend.printStackTrace(System.err);
                try
                {
                    session.close(new CloseReason(CloseReason.CloseCodes.getCloseCode(4001), "Unable to echo msg"));
                }
                catch (IOException eclose)
                {
                    eclose.printStackTrace();
                }
            }
        }
    }

    @SuppressWarnings("unused")
    @ServerEndpoint("/echo/basic-stateless")
    public static class EchoBasicStatelessTextSocket
    {
        @OnMessage
        public void onText(Session session, String msg)
        {
            try
            {
                session.getBasicRemote().sendText(msg);
            }
            catch (IOException esend)
            {
                esend.printStackTrace(System.err);
                try
                {
                    session.close(new CloseReason(CloseReason.CloseCodes.getCloseCode(4001), "Unable to echo msg"));
                }
                catch (IOException eclose)
                {
                    eclose.printStackTrace();
                }
            }
        }
    }

    @SuppressWarnings("unused")
    @ServerEndpoint("/echo/async")
    public static class EchoAsyncTextSocket
    {
        private Session session;

        @OnOpen
        public void onOpen(Session session)
        {
            this.session = session;
        }

        @OnMessage
        public void onText(String msg)
        {
            session.getAsyncRemote().sendText(msg);
        }
    }

    @SuppressWarnings("unused")
    @ServerEndpoint("/echo/async-stateless")
    public static class EchoAsyncStatelessSocket
    {
        @OnMessage
        public void onText(Session session, String msg)
        {
            session.getAsyncRemote().sendText(msg);
        }
    }

    @SuppressWarnings("unused")
    @ServerEndpoint("/echo/text/return")
    public static class EchoReturnTextSocket
    {
        @OnMessage
        public String onText(String msg)
        {
            return msg;
        }
    }

    private static final List<Class<?>> TESTCLASSES = Arrays.asList(
        EchoBasicTextSocket.class,
        EchoBasicStatelessTextSocket.class,
        EchoAsyncTextSocket.class,
        EchoAsyncStatelessSocket.class,
        EchoReturnTextSocket.class);

    public static Stream<Arguments> data()
    {
        List<Arguments> data = new ArrayList<>();

        for (Class<?> clazz : TESTCLASSES)
        {
            data.add(Arguments.of(clazz.getSimpleName(), clazz));
        }

        return data.stream();
    }

    private static LocalServer server;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new LocalServer();
        server.start();
        ServerContainer container = server.getServerContainer();
        for (Class<?> clazz : TESTCLASSES)
        {
            container.addEndpoint(clazz);
        }
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void testTextEcho(String endpointClassname, Class<?> endpointClass) throws Exception
    {
        String requestPath = endpointClass.getAnnotation(ServerEndpoint.class).value();

        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload("Hello Echo"));
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.TEXT).setPayload("Hello Echo"));
        expect.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        try (Fuzzer session = server.newNetworkFuzzer(requestPath))
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
}
