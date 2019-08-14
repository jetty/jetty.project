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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.javax.tests.Fuzzer;
import org.eclipse.jetty.websocket.javax.tests.WSServer;
import org.eclipse.jetty.websocket.javax.tests.coders.DateDecoder;
import org.eclipse.jetty.websocket.javax.tests.coders.TimeEncoder;
import org.eclipse.jetty.websocket.javax.tests.server.configs.EchoSocketConfigurator;
import org.eclipse.jetty.websocket.javax.tests.server.sockets.ConfiguredEchoSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Example of an annotated echo server discovered via annotation scanning.
 */
public class AnnotatedServerEndpointTest
{
    private static WSServer server;

    @BeforeAll
    public static void startServer() throws Exception
    {
        Path testdir = MavenTestingUtils.getTargetTestingPath(AnnotatedServerEndpointTest.class.getName());
        server = new WSServer(testdir, "app");
        server.createWebInf();
        server.copyEndpoint(ConfiguredEchoSocket.class);
        server.copyClass(EchoSocketConfigurator.class);
        server.copyClass(DateDecoder.class);
        server.copyClass(TimeEncoder.class);

        server.start();

        WebAppContext webapp = server.createWebAppContext();
        server.deployWebapp(webapp);
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    private void assertResponse(String message, String expectedText) throws Exception
    {
        Map<String, String> headers = new HashMap<>();
        headers.put(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL.asString(), "echo");

        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload(message));
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.TEXT).setPayload(expectedText));
        expect.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        try (Fuzzer session = server.newNetworkFuzzer("/app/echo", headers))
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
}
