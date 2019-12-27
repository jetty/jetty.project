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
import java.util.List;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.javax.tests.Fuzzer;
import org.eclipse.jetty.websocket.javax.tests.LocalServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Sends raw TEXT or BINARY messages to server.
 * <p>
 * JSR356 Decoder resolves it to an object, and uses the JSR356 Encoder to produce an echo response.
 * </p>
 */
public class PartialEchoTest
{
    private static final Logger LOG = Log.getLogger(PartialEchoTest.class);

    public static class BaseSocket
    {
        @OnError
        public void onError(Throwable cause) throws IOException
        {
            LOG.warn("Error", cause);
        }
    }

    @SuppressWarnings("unused")
    @ServerEndpoint("/echo/partial/text")
    public static class PartialTextSocket extends BaseSocket
    {
        private Session session;
        private StringBuilder buf = new StringBuilder();

        @OnOpen
        public void onOpen(Session session)
        {
            this.session = session;
        }

        @SuppressWarnings("IncorrectOnMessageMethodsInspection")
        @OnMessage
        public void onPartial(String msg, boolean fin) throws IOException
        {
            buf.append("('").append(msg).append("',").append(fin).append(')');
            if (fin)
            {
                session.getBasicRemote().sendText(buf.toString());
                buf.setLength(0);
            }
        }
    }

    @SuppressWarnings("unused")
    @ServerEndpoint("/echo/partial/text-session")
    public static class PartialTextSessionSocket extends BaseSocket
    {
        private StringBuilder buf = new StringBuilder();

        @SuppressWarnings("IncorrectOnMessageMethodsInspection")
        @OnMessage
        public void onPartial(String msg, boolean fin, Session session) throws IOException
        {
            buf.append("('").append(msg).append("',").append(fin).append(')');
            if (fin)
            {
                session.getBasicRemote().sendText(buf.toString());
                buf.setLength(0);
            }
        }
    }

    private static LocalServer server;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new LocalServer();
        server.start();
        server.getServerContainer().addEndpoint(PartialTextSocket.class);
        server.getServerContainer().addEndpoint(PartialTextSessionSocket.class);
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testPartialText() throws Exception
    {
        String requestPath = "/echo/partial/text";

        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload("Hello").setFin(false));
        send.add(new Frame(OpCode.CONTINUATION).setPayload(", ").setFin(false));
        send.add(new Frame(OpCode.CONTINUATION).setPayload("World").setFin(true));
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.TEXT).setPayload("('Hello',false)(', ',false)('World',true)"));
        expect.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        try (Fuzzer session = server.newNetworkFuzzer(requestPath))
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    @Test
    public void testPartialTextSession() throws Exception
    {
        String requestPath = "/echo/partial/text-session";

        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload("Hello").setFin(false));
        send.add(new Frame(OpCode.CONTINUATION).setPayload(", ").setFin(false));
        send.add(new Frame(OpCode.CONTINUATION).setPayload("World").setFin(true));
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.TEXT).setPayload("('Hello',false)(', ',false)('World',true)"));
        expect.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        try (Fuzzer session = server.newNetworkFuzzer(requestPath))
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
}
