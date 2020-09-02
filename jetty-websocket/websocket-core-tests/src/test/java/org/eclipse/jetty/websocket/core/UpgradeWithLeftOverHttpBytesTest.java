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
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.internal.Generator;
import org.eclipse.jetty.websocket.core.internal.WebSocketCore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        TestMessageHandler clientEndpoint = new TestMessageHandler();
        CompletableFuture<CoreSession> clientConnect = client.connect(clientEndpoint, serverUri);
        Socket serverSocket = server.accept();

        String upgradeRequest = getRequestHeaders(serverSocket.getInputStream());
        assertThat(upgradeRequest, containsString("HTTP/1.1"));
        assertThat(upgradeRequest, containsString("Upgrade: websocket"));

        // Send upgrade response in the same write as two websocket frames.
        String upgradeResponse = "HTTP/1.1 101 Switching Protocols\n" +
            "Upgrade: WebSocket\n" +
            "Connection: Upgrade\n" +
            "Sec-WebSocket-Accept: " + getAcceptKey(upgradeRequest) + "\n" +
            "\n";

        Frame dataFrame = new Frame(OpCode.TEXT, BufferUtil.toBuffer("first message payload"));
        Frame closeFrame = new CloseStatus(CloseStatus.NORMAL, "closed by test").toFrame();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(upgradeResponse.getBytes(StandardCharsets.ISO_8859_1));
        BufferUtil.writeTo(generateFrame(dataFrame), baos);
        BufferUtil.writeTo(generateFrame(closeFrame), baos);
        serverSocket.getOutputStream().write(baos.toByteArray());

        // Check the client receives upgrade response and then the two websocket frames.
        CoreSession coreSession = clientConnect.get(5, TimeUnit.SECONDS);
        assertNotNull(coreSession);
        assertTrue(clientEndpoint.openLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS), is("first message payload"));
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
}
