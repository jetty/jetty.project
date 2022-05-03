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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.Fuzzer;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.LocalServer;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UriTemplateParameterTest
{
    private static final Logger LOG = LoggerFactory.getLogger(UriTemplateParameterTest.class);

    @ServerEndpoint("/echo/params/{a}/{b}")
    public static class IntParamTextSocket
    {
        @OnMessage
        public String onMessage(int i, @PathParam("a") int paramA, @PathParam("b") int paramB) throws IOException
        {
            return String.format("%,d|%,d|%,d", i, paramA, paramB);
        }

        @OnError
        public void onError(Throwable cause) throws IOException
        {
            LOG.warn("Error", cause);
        }
    }

    private static LocalServer server;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new LocalServer();
        server.start();
        server.getServerContainer().addEndpoint(IntParamTextSocket.class);
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testIntParams() throws Exception
    {
        String requestPath = "/echo/params/1234/5678";

        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload("9999"));
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.TEXT).setPayload("9,999|1,234|5,678"));
        expect.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        try (Fuzzer session = server.newNetworkFuzzer(requestPath))
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
}
