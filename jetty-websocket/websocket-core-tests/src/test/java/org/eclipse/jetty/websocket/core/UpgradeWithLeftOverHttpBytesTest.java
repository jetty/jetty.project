//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.internal.Generator;
import org.eclipse.jetty.websocket.core.internal.WebSocketCore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UpgradeWithLeftOverHttpBytesTest extends WebSocketTester
{
    private ServerSocket server;
    private URI serverUri;
    private WebSocketCoreClient client;
    private final Generator generator = new Generator();

    @BeforeEach
    public void start() throws Exception
    {
        client = new WebSocketCoreClient();
        client.getHttpClient().setIdleTimeout(5000);
        client.start();
        server = new ServerSocket(0);
        serverUri = URI.create("ws://localhost:" + server.getLocalPort());
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.close();
    }

    @Test
    public void testUpgradeWithLeftOverHttpBytes() throws Exception
    {
        CountDownLatch onOpenWait = new CountDownLatch(1);
        TestMessageHandler clientEndpoint = new TestMessageHandler()
        {
            @Override
            public void onOpen(CoreSession coreSession, Callback callback)
            {
                assertDoesNotThrow(() -> onOpenWait.await(5, TimeUnit.SECONDS));
                super.onOpen(coreSession, callback);
            }
        };
        CoreClientUpgradeRequest coreUpgrade = CoreClientUpgradeRequest.from(client, serverUri, clientEndpoint);
        client.connect(coreUpgrade);
        Socket serverSocket = server.accept();

        // Receive the upgrade request with the Socket.
        String upgradeRequest = getRequestHeaders(serverSocket.getInputStream());
        assertThat(upgradeRequest, containsString("HTTP/1.1"));
        assertThat(upgradeRequest, containsString("Upgrade: websocket"));

        // Send upgrade response in the same write as two websocket frames.
        String upgradeResponse = "HTTP/1.1 101 Switching Protocols\n" +
            "Upgrade: WebSocket\n" +
            "Connection: Upgrade\n" +
            "Sec-WebSocket-Accept: " + getAcceptKey(upgradeRequest) + "\n" +
            "\n";
        Frame firstFrame = new Frame(OpCode.TEXT, BufferUtil.toBuffer("first message payload"));
        byte[] bytes = combineToByteArray(BufferUtil.toBuffer(upgradeResponse), generateFrame(firstFrame));
        serverSocket.getOutputStream().write(bytes);

        // Now we send the rest of the data.
        int numFrames = 1000;
        for (int i = 0; i < numFrames; i++)
        {
            Frame frame = new Frame(OpCode.TEXT, BufferUtil.toBuffer(Integer.toString(i)));
            serverSocket.getOutputStream().write(toByteArray(frame));
        }
        Frame closeFrame = new CloseStatus(CloseStatus.NORMAL, "closed by test").toFrame();
        serverSocket.getOutputStream().write(toByteArray(closeFrame));

        // First payload sent with upgrade request, delay to ensure HttpConnection is not still reading from network.
        Thread.sleep(1000);
        onOpenWait.countDown();
        assertTrue(clientEndpoint.openLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS), is("first message payload"));

        // We receive the rest of the frames all sent as separate writes.
        for (int i = 0; i < numFrames; i++)
        {
            String msg = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
            assertThat(msg, is(Integer.toString(i)));
        }

        // Closed successfully with correct status.
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeStatus.getCode(), is(CloseStatus.NORMAL));
        assertThat(clientEndpoint.closeStatus.getReason(), is("closed by test"));
    }

    public ByteBuffer generateFrame(Frame frame)
    {
        int size = Generator.MAX_HEADER_LENGTH + frame.getPayloadLength();
        ByteBuffer buffer = BufferUtil.allocate(size);
        generator.generateWholeFrame(frame, buffer);
        return buffer;
    }

    String getAcceptKey(String upgradeRequest)
    {
        Matcher matcher = Pattern.compile(".*Sec-WebSocket-Key: ([^\n\r]+)\r?\n.*", Pattern.DOTALL | Pattern.MULTILINE)
            .matcher(upgradeRequest);
        assertTrue(matcher.matches());
        String key = matcher.group(1);
        assertFalse(StringUtil.isEmpty(key));
        return WebSocketCore.hashKey(key);
    }

    static String getRequestHeaders(InputStream is)
    {
        Scanner s = new Scanner(is).useDelimiter("\r\n\r\n");
        return s.hasNext() ? s.next() : "";
    }

    byte[] combineToByteArray(ByteBuffer... buffers) throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (ByteBuffer bb : buffers)
        {
            BufferUtil.writeTo(bb, baos);
        }

        return baos.toByteArray();
    }

    byte[] toByteArray(Frame frame)
    {
        return BufferUtil.toArray(generateFrame(frame));
    }
}
