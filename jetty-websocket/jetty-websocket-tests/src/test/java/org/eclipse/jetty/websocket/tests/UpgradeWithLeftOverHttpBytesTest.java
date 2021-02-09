//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.tests;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.AcceptHash;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UpgradeWithLeftOverHttpBytesTest
{
    private ServerSocket server;
    private URI serverUri;
    private WebSocketClient client;
    private final Generator generator = new Generator(WebSocketPolicy.newServerPolicy(), new MappedByteBufferPool());

    @BeforeEach
    public void start() throws Exception
    {
        client = new WebSocketClient();
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

    @WebSocket
    public static class OnOpenSocket extends EventSocket
    {
        CountDownLatch onOpenBlocked = new CountDownLatch(1);

        @Override
        public void onOpen(Session session)
        {
            super.onOpen(session);
            assertDoesNotThrow(() -> assertTrue(onOpenBlocked.await(1, TimeUnit.MINUTES)));
        }
    }

    @Test
    public void testRequestCompletesFirstNoWebSocketBytesInResponse() throws Exception
    {
        // Initiate connection.
        OnOpenSocket clientEndpoint = new OnOpenSocket();
        client.connect(clientEndpoint, serverUri);
        Socket serverSocket = server.accept();

        // Upgrade to WebSocket.
        String upgradeRequest = getRequestHeaders(serverSocket.getInputStream());
        assertThat(upgradeRequest, containsString("HTTP/1.1"));
        assertThat(upgradeRequest, containsString("Upgrade: websocket"));
        String upgradeResponse = "HTTP/1.1 101 Switching Protocols\n" +
            "Upgrade: WebSocket\n" +
            "Connection: Upgrade\n" +
            "Sec-WebSocket-Accept: " + getAcceptKey(upgradeRequest) + "\n" +
            "\n";
        serverSocket.getOutputStream().write(upgradeResponse.getBytes(StandardCharsets.ISO_8859_1));

        // Wait for WebSocket to be opened, wait 1 sec before allowing it to continue.
        assertTrue(clientEndpoint.openLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(1000);
        clientEndpoint.onOpenBlocked.countDown();

        // Send some websocket data.
        int numFrames = 1000;
        for (int i = 0; i < numFrames; i++)
        {
            Frame frame = new TextFrame().setPayload(BufferUtil.toBuffer(Integer.toString(i)));
            serverSocket.getOutputStream().write(toByteArray(frame));
        }
        Frame closeFrame = new CloseInfo(StatusCode.NORMAL, "closed by test").asFrame();
        serverSocket.getOutputStream().write(toByteArray(closeFrame));

        // We receive the data correctly.
        for (int i = 0; i < numFrames; i++)
        {
            String msg = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
            assertThat(msg, is(Integer.toString(i)));
        }

        // Closed successfully with correct status.
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, is(StatusCode.NORMAL));
        assertThat(clientEndpoint.closeReason, is("closed by test"));
    }

    @Test
    public void testRequestCompletesFirstWithWebSocketBytesInResponse() throws Exception
    {
        // Initiate connection.
        OnOpenSocket clientEndpoint = new OnOpenSocket();
        client.connect(clientEndpoint, serverUri);
        Socket serverSocket = server.accept();

        // Upgrade to WebSocket, sending first websocket frame with the upgrade response.
        String upgradeRequest = getRequestHeaders(serverSocket.getInputStream());
        assertThat(upgradeRequest, containsString("HTTP/1.1"));
        assertThat(upgradeRequest, containsString("Upgrade: websocket"));
        String upgradeResponse = "HTTP/1.1 101 Switching Protocols\n" +
            "Upgrade: WebSocket\n" +
            "Connection: Upgrade\n" +
            "Sec-WebSocket-Accept: " + getAcceptKey(upgradeRequest) + "\n" +
            "\n";
        Frame firstFrame = new TextFrame().setPayload("first message payload");
        byte[] bytes = combineToByteArray(BufferUtil.toBuffer(upgradeResponse), generateFrame(firstFrame));
        serverSocket.getOutputStream().write(bytes);

        // Now we send the rest of the data.
        int numFrames = 1000;
        for (int i = 0; i < numFrames; i++)
        {
            Frame frame = new TextFrame().setPayload(BufferUtil.toBuffer(Integer.toString(i)));
            serverSocket.getOutputStream().write(toByteArray(frame));
        }
        Frame closeFrame = new CloseInfo(StatusCode.NORMAL, "closed by test").asFrame();
        serverSocket.getOutputStream().write(toByteArray(closeFrame));

        // Wait for WebSocket to be opened, wait 1 sec before allowing it to continue.
        // We delay to ensure HttpConnection is not still reading from network.
        assertTrue(clientEndpoint.openLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(1000);
        clientEndpoint.onOpenBlocked.countDown();

        // We receive the data correctly.
        assertThat(clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS), is("first message payload"));
        for (int i = 0; i < numFrames; i++)
        {
            String msg = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
            assertThat(msg, is(Integer.toString(i)));
        }

        // Closed successfully with correct status.
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, is(StatusCode.NORMAL));
        assertThat(clientEndpoint.closeReason, is("closed by test"));
    }

    @Test
    public void testResponseCompletesFirstNoWebSocketBytesInResponse() throws Exception
    {
        // We delay the request to finish until after the response is complete.
        client.addBean(new Request.Listener()
        {
            @Override
            public void onCommit(Request request)
            {
                assertDoesNotThrow(() -> Thread.sleep(1000));
            }
        });

        // Initiate connection.
        OnOpenSocket clientEndpoint = new OnOpenSocket();
        client.connect(clientEndpoint, serverUri);
        Socket serverSocket = server.accept();

        // Upgrade to WebSocket.
        String upgradeRequest = getRequestHeaders(serverSocket.getInputStream());
        assertThat(upgradeRequest, containsString("HTTP/1.1"));
        assertThat(upgradeRequest, containsString("Upgrade: websocket"));
        String upgradeResponse = "HTTP/1.1 101 Switching Protocols\n" +
            "Upgrade: WebSocket\n" +
            "Connection: Upgrade\n" +
            "Sec-WebSocket-Accept: " + getAcceptKey(upgradeRequest) + "\n" +
            "\n";
        serverSocket.getOutputStream().write(upgradeResponse.getBytes(StandardCharsets.ISO_8859_1));

        // Wait for WebSocket to be opened, wait 1 sec before allowing it to continue.
        assertTrue(clientEndpoint.openLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(1000);
        clientEndpoint.onOpenBlocked.countDown();

        // Send some websocket data.
        int numFrames = 1000;
        for (int i = 0; i < numFrames; i++)
        {
            Frame frame = new TextFrame().setPayload(BufferUtil.toBuffer(Integer.toString(i)));
            serverSocket.getOutputStream().write(toByteArray(frame));
        }
        Frame closeFrame = new CloseInfo(StatusCode.NORMAL, "closed by test").asFrame();
        serverSocket.getOutputStream().write(toByteArray(closeFrame));

        // We receive the data correctly.
        for (int i = 0; i < numFrames; i++)
        {
            String msg = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
            assertThat(msg, is(Integer.toString(i)));
        }

        // Closed successfully with correct status.
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, is(StatusCode.NORMAL));
        assertThat(clientEndpoint.closeReason, is("closed by test"));
    }

    @Test
    public void testResponseCompletesFirstWithWebSocketBytesInResponse() throws Exception
    {
        // We delay the request to finish until after the response is complete.
        client.addBean(new Request.Listener()
        {
            @Override
            public void onCommit(Request request)
            {
                assertDoesNotThrow(() -> Thread.sleep(1000));
            }
        });

        // Initiate connection.
        OnOpenSocket clientEndpoint = new OnOpenSocket();
        client.connect(clientEndpoint, serverUri);
        Socket serverSocket = server.accept();

        // Upgrade to WebSocket, sending first websocket frame with the upgrade response.
        String upgradeRequest = getRequestHeaders(serverSocket.getInputStream());
        assertThat(upgradeRequest, containsString("HTTP/1.1"));
        assertThat(upgradeRequest, containsString("Upgrade: websocket"));
        String upgradeResponse = "HTTP/1.1 101 Switching Protocols\n" +
            "Upgrade: WebSocket\n" +
            "Connection: Upgrade\n" +
            "Sec-WebSocket-Accept: " + getAcceptKey(upgradeRequest) + "\n" +
            "\n";
        Frame firstFrame = new TextFrame().setPayload("first message payload");
        byte[] bytes = combineToByteArray(BufferUtil.toBuffer(upgradeResponse), generateFrame(firstFrame));
        serverSocket.getOutputStream().write(bytes);

        // Now we send the rest of the data.
        int numFrames = 1000;
        for (int i = 0; i < numFrames; i++)
        {
            Frame frame = new TextFrame().setPayload(BufferUtil.toBuffer(Integer.toString(i)));
            serverSocket.getOutputStream().write(toByteArray(frame));
        }
        Frame closeFrame = new CloseInfo(StatusCode.NORMAL, "closed by test").asFrame();
        serverSocket.getOutputStream().write(toByteArray(closeFrame));

        // Wait for WebSocket to be opened, wait 1 sec before allowing it to continue.
        // We delay to ensure HttpConnection is not still reading from network.
        assertTrue(clientEndpoint.openLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(1000);
        clientEndpoint.onOpenBlocked.countDown();

        // We receive the data correctly.
        assertThat(clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS), is("first message payload"));
        for (int i = 0; i < numFrames; i++)
        {
            String msg = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
            assertThat(msg, is(Integer.toString(i)));
        }

        // Closed successfully with correct status.
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, is(StatusCode.NORMAL));
        assertThat(clientEndpoint.closeReason, is("closed by test"));
    }

    public ByteBuffer generateFrame(Frame frame)
    {
        int size = Generator.MAX_HEADER_LENGTH + frame.getPayloadLength();
        ByteBuffer buffer = BufferUtil.allocate(size);
        int pos = BufferUtil.flipToFill(buffer);
        generator.generateWholeFrame(frame, buffer);
        BufferUtil.flipToFlush(buffer, pos);
        return buffer;
    }

    String getAcceptKey(String upgradeRequest)
    {
        Matcher matcher = Pattern.compile(".*Sec-WebSocket-Key: ([^\n\r]+)\r?\n.*", Pattern.DOTALL | Pattern.MULTILINE)
            .matcher(upgradeRequest);
        assertTrue(matcher.matches());
        String key = matcher.group(1);
        assertFalse(StringUtil.isEmpty(key));
        return AcceptHash.hashKey(key);
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
