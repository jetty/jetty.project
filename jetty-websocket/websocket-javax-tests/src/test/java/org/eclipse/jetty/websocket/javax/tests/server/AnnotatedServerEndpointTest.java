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

package org.eclipse.jetty.websocket.javax.tests.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.javax.tests.Fuzzer;
import org.eclipse.jetty.websocket.javax.tests.LocalServer;
import org.eclipse.jetty.websocket.javax.tests.coders.DateDecoder;
import org.eclipse.jetty.websocket.javax.tests.coders.TimeEncoder;
import org.eclipse.jetty.websocket.javax.tests.server.configs.EchoSocketConfigurator;
import org.eclipse.jetty.websocket.javax.tests.server.sockets.ConfiguredEchoSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AnnotatedServerEndpointTest
{
    private LocalServer server;
    private String path = "/echo";
    private String subprotocol = "echo";

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new LocalServer();
        server.start();
        server.getServerContainer().addEndpoint(ConfiguredEchoSocket.class);

        ServerEndpointConfig endpointConfig = ServerEndpointConfig.Builder
            .create(ConfiguredEchoSocket.class, "/override")
            .subprotocols(Collections.singletonList("override"))
            .build();
        server.getServerContainer().addEndpoint(endpointConfig);
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    private void assertResponse(String message, String expectedText) throws Exception
    {
        Map<String, String> headers = new HashMap<>();
        headers.put(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL.asString(), subprotocol);

        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload(message));
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.TEXT).setPayload(expectedText));
        expect.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        try (Fuzzer session = server.newNetworkFuzzer(path, headers))
        {
            session.sendFrames(send);
            session.expect(expect);
        }
    }

    @Test
    public void testConfigurator() throws Exception
    {
        assertResponse("configurator", EchoSocketConfigurator.class.getName());
    }

    @Test
    public void testTextMax() throws Exception
    {
        assertResponse("text-max", "111,222");
    }

    @Test
    public void testBinaryMax() throws Exception
    {
        assertResponse("binary-max", "333,444");
    }

    @Test
    public void testDecoders() throws Exception
    {
        assertResponse("decoders", DateDecoder.class.getName());
    }

    @Test
    public void testEncoders() throws Exception
    {
        assertResponse("encoders", TimeEncoder.class.getName());
    }

    @Test
    public void testSubProtocols() throws Exception
    {
        assertResponse("subprotocols", "chat, echo, test");
    }

    @Test
    public void testOverrideEndpointConfig() throws Exception
    {
        this.path = "/override";
        this.subprotocol = "override";

        assertResponse("configurator", EchoSocketConfigurator.class.getName());
        assertResponse("text-max", "111,222");
        assertResponse("binary-max", "333,444");
        assertResponse("decoders", DateDecoder.class.getName());
        assertResponse("encoders", TimeEncoder.class.getName());
        assertResponse("subprotocols", "override");
    }
}
