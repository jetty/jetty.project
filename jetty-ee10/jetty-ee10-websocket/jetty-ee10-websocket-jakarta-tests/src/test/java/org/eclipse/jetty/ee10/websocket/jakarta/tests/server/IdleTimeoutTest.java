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
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import com.acme.websocket.IdleTimeoutContextListener;
import com.acme.websocket.IdleTimeoutOnOpenEndpoint;
import com.acme.websocket.IdleTimeoutOnOpenSocket;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.Fuzzer;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.WSServer;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class IdleTimeoutTest
{
    private static WSServer server;

    @BeforeAll
    public static void setupServer() throws Exception
    {
        server = new WSServer(MavenTestingUtils.getTargetTestingPath(IdleTimeoutTest.class.getName()));
        WSServer.WebApp app = server.createWebApp("app");
        app.copyWebInf("idle-timeout-config-web.xml");
        // the endpoint (extends jakarta.websocket.Endpoint)
        app.copyClass(IdleTimeoutOnOpenEndpoint.class);
        // the configuration that adds the endpoint
        app.copyClass(IdleTimeoutContextListener.class);
        // the annotated socket
        app.copyClass(IdleTimeoutOnOpenSocket.class);
        app.deploy();

        server.start();
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    private void assertConnectionTimeout(String requestPath) throws Exception
    {
        try (Fuzzer session = server.newNetworkFuzzer(requestPath);
             StacklessLogging stacklessLogging = new StacklessLogging(IdleTimeoutOnOpenSocket.class))
        {
            // wait 1 second to allow timeout to fire off
            TimeUnit.SECONDS.sleep(1);

            IOException error = assertThrows(IOException.class,
                () -> session.sendFrames(new Frame(OpCode.TEXT).setPayload("You shouldn't be there")));
            assertThat(error.getCause(), instanceOf(ClosedChannelException.class));

            BlockingQueue<Frame> framesQueue = session.getOutputFrames();
            Frame frame = framesQueue.poll(1, TimeUnit.SECONDS);
            assertThat("Frame.opCode", frame.getOpCode(), is(OpCode.CLOSE));
            CloseStatus closeStatus = new CloseStatus(frame.getPayload());
            assertThat("Close.statusCode", closeStatus.getCode(), is(CloseStatus.SHUTDOWN));
            assertThat("Close.reason", closeStatus.getReason(), containsString("Timeout"));
        }
    }

    @Test
    public void testAnnotated() throws Exception
    {
        assertConnectionTimeout("/app/idle-onopen-socket");
    }

    @Test
    public void testEndpoint() throws Exception
    {
        assertConnectionTimeout("/app/idle-onopen-endpoint");
    }
}
