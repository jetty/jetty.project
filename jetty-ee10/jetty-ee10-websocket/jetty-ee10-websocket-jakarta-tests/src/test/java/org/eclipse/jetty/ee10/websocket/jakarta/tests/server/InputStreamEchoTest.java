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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.server;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.Fuzzer;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.LocalServer;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test various {@link jakarta.websocket.Decoder.BinaryStream Decoder.BinaryStream} echo behavior of Java InputStreams
 */
public class InputStreamEchoTest
{
    private static final Logger LOG = LoggerFactory.getLogger(InputStreamEchoTest.class);

    public static class BaseSocket
    {
        @OnError
        public void onError(Throwable cause) throws IOException
        {
            LOG.warn("Error", cause);
        }
    }

    @SuppressWarnings("unused")
    @ServerEndpoint("/echo/stream")
    public static class InputStreamSocket extends BaseSocket
    {
        @OnMessage
        public String onStream(InputStream stream) throws IOException
        {
            return IO.toString(stream);
        }
    }

    @SuppressWarnings("unused")
    @ServerEndpoint("/echo/stream-param/{param}")
    public static class InputStreamParamSocket extends BaseSocket
    {
        @OnMessage
        public String onStream(InputStream stream, @PathParam("param") String param) throws IOException
        {
            StringBuilder msg = new StringBuilder();
            msg.append(IO.toString(stream));
            msg.append('|');
            msg.append(param);
            return msg.toString();
        }
    }

    private static LocalServer server;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new LocalServer();
        server.start();
        server.getServerContainer().addEndpoint(InputStreamSocket.class);
        server.getServerContainer().addEndpoint(InputStreamParamSocket.class);
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testInputStreamSocket() throws Exception
    {
        String requestPath = "/echo/stream";

        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.BINARY).setPayload("Hello World"));
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.TEXT).setPayload("Hello World"));
        expect.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        try (Fuzzer session = server.newNetworkFuzzer(requestPath))
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }

    @Test
    public void testInputStreamParamSocket() throws Exception
    {
        String requestPath = "/echo/stream-param/Every%20Person";

        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.BINARY).setPayload("Hello World"));
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.TEXT).setPayload("Hello World|Every%20Person"));
        expect.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        try (Fuzzer session = server.newNetworkFuzzer(requestPath))
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
}
