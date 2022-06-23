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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.acme.websocket.LargeEchoDefaultSocket;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.WSServer;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.framehandlers.FrameHandlerTracker;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Test Echo of Large messages, targeting the {@link jakarta.websocket.WebSocketContainer#setDefaultMaxTextMessageBufferSize(int)} functionality
 */
@ExtendWith(WorkDirExtension.class)
public class LargeContainerTest
{
    public WorkDir testdir;

    @SuppressWarnings("Duplicates")
    @Disabled
    @Test
    public void testEcho() throws Exception
    {
        WSServer wsb = new WSServer(testdir.getPath());
        WSServer.WebApp app = wsb.createWebApp("app");
        app.copyWebInf("large-echo-config-web.xml");
        app.copyClass(LargeEchoDefaultSocket.class);
        app.deploy();

        try
        {
            wsb.start();
            URI uri = wsb.getWsUri();

            WebSocketCoreClient client = new WebSocketCoreClient();
            try
            {
                client.start();

                FrameHandlerTracker clientSocket = new FrameHandlerTracker();
                Future<CoreSession> clientConnectFuture = client.connect(clientSocket, uri.resolve("/app/echo/large"));

                // wait for connect
                CoreSession coreSession = clientConnectFuture.get(5, TimeUnit.SECONDS);
                coreSession.setMaxTextMessageSize(128 * 1024);
                try
                {
                    // The message size should be bigger than default, but smaller than the limit that LargeEchoSocket specifies
                    byte[] txt = new byte[100 * 1024];
                    Arrays.fill(txt, (byte)'o');
                    String msg = new String(txt, StandardCharsets.UTF_8);
                    coreSession.sendFrame(new Frame(OpCode.TEXT).setPayload(msg), Callback.NOOP, false);

                    // Confirm echo
                    String incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
                    assertThat("Expected message", incomingMessage, is(msg));
                }
                finally
                {
                    coreSession.close(Callback.NOOP);
                }
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            wsb.stop();
        }
    }
}
