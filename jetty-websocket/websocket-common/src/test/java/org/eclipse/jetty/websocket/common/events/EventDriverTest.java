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

package org.eclipse.jetty.websocket.common.events;

import examples.AdapterConnectCloseSocket;
import examples.AnnotatedBinaryArraySocket;
import examples.AnnotatedBinaryStreamSocket;
import examples.AnnotatedFramesSocket;
import examples.AnnotatedTextSocket;
import examples.ListenerBasicSocket;
import examples.ListenerPingPongSocket;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.frames.BinaryFrame;
import org.eclipse.jetty.websocket.common.frames.PingFrame;
import org.eclipse.jetty.websocket.common.frames.PongFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.io.CloseableLocalWebSocketSession;
import org.eclipse.jetty.websocket.common.io.LocalWebSocketSession;
import org.eclipse.jetty.websocket.common.scopes.SimpleContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.eclipse.jetty.websocket.common.test.MoreMatchers.regex;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

public class EventDriverTest
{
    public ByteBufferPool bufferPool = new MappedByteBufferPool();

    private WebSocketContainerScope container;

    @BeforeEach
    public void initContainer()
    {
        this.container = new SimpleContainerScope(WebSocketPolicy.newClientPolicy());
    }

    private Frame makeBinaryFrame(String content, boolean fin)
    {
        return new BinaryFrame().setPayload(content).setFin(fin);
    }

    @Test
    public void testAdapterConnectClose(TestInfo testInfo) throws Exception
    {
        AdapterConnectCloseSocket socket = new AdapterConnectCloseSocket();
        EventDriver driver = wrap(socket);

        try (LocalWebSocketSession conn = new CloseableLocalWebSocketSession(container, testInfo.getDisplayName(), driver))
        {
            conn.open();
            driver.incomingFrame(new CloseInfo(StatusCode.NORMAL).asFrame());

            assertThat(socket.capture.safePoll(), startsWith("onWebSocketConnect"));
            assertThat(socket.capture.safePoll(), startsWith("onWebSocketClose"));
        }
    }

    @Test
    public void testAnnotatedByteArray(TestInfo testInfo) throws Exception
    {
        AnnotatedBinaryArraySocket socket = new AnnotatedBinaryArraySocket();
        EventDriver driver = wrap(socket);

        try (LocalWebSocketSession conn = new CloseableLocalWebSocketSession(container, testInfo.getDisplayName(), driver))
        {
            conn.open();
            driver.incomingFrame(makeBinaryFrame("Hello World", true));
            driver.incomingFrame(new CloseInfo(StatusCode.NORMAL).asFrame());

            assertThat(socket.capture.safePoll(), startsWith("onConnect"));
            assertThat(socket.capture.safePoll(), containsString("onBinary([11],0,11)"));
            assertThat(socket.capture.safePoll(), startsWith("onClose(1000,"));
        }
    }

    @Test
    public void testAnnotatedError(TestInfo testInfo) throws Exception
    {
        AnnotatedTextSocket socket = new AnnotatedTextSocket();
        EventDriver driver = wrap(socket);

        try (LocalWebSocketSession conn = new CloseableLocalWebSocketSession(container, testInfo.getDisplayName(), driver))
        {
            conn.open();
            driver.onError(new WebSocketException("oof"));
            driver.incomingFrame(new CloseInfo(StatusCode.NORMAL).asFrame());

            assertThat(socket.capture.safePoll(), startsWith("onConnect"));
            assertThat(socket.capture.safePoll(), startsWith("onError(WebSocketException: oof)"));
            assertThat(socket.capture.safePoll(), startsWith("onClose(1000,"));
        }
    }

    @Test
    public void testAnnotatedFrames(TestInfo testInfo) throws Exception
    {
        AnnotatedFramesSocket socket = new AnnotatedFramesSocket();
        EventDriver driver = wrap(socket);

        try (LocalWebSocketSession conn = new CloseableLocalWebSocketSession(container, testInfo.getDisplayName(), driver))
        {
            conn.open();
            driver.incomingFrame(new PingFrame().setPayload("PING"));
            driver.incomingFrame(new TextFrame().setPayload("Text Me"));
            driver.incomingFrame(new BinaryFrame().setPayload("Hello Bin"));
            driver.incomingFrame(new CloseInfo(StatusCode.SHUTDOWN, "testcase").asFrame());

            assertThat(socket.capture.safePoll(), startsWith("onConnect("));
            assertThat(socket.capture.safePoll(), startsWith("onFrame(PING["));
            assertThat(socket.capture.safePoll(), startsWith("onFrame(TEXT["));
            assertThat(socket.capture.safePoll(), startsWith("onFrame(BINARY["));
            assertThat(socket.capture.safePoll(), startsWith("onFrame(CLOSE["));
            assertThat(socket.capture.safePoll(), startsWith("onClose(1001,"));
        }
    }

    @Test
    public void testAnnotatedInputStream(TestInfo testInfo) throws InterruptedException
    {
        AnnotatedBinaryStreamSocket socket = new AnnotatedBinaryStreamSocket();
        EventDriver driver = wrap(socket);

        try (LocalWebSocketSession conn = new CloseableLocalWebSocketSession(container, testInfo.getDisplayName(), driver))
        {
            conn.open();
            driver.incomingFrame(makeBinaryFrame("Hello World", true));
            driver.incomingFrame(new CloseInfo(StatusCode.NORMAL).asFrame());

            assertThat(socket.capture.safePoll(), startsWith("onConnect"));
            assertThat(socket.capture.safePoll(), regex("^onBinary\\(.*InputStream.*"));
            assertThat(socket.capture.safePoll(), startsWith("onClose(1000,"));
        }
    }

    @Test
    public void testListenerBasicText(TestInfo testInfo) throws Exception
    {
        ListenerBasicSocket socket = new ListenerBasicSocket();
        EventDriver driver = wrap(socket);

        try (LocalWebSocketSession conn = new CloseableLocalWebSocketSession(container, testInfo.getDisplayName(), driver))
        {
            conn.start();
            conn.open();
            driver.incomingFrame(new TextFrame().setPayload("Hello World"));
            driver.incomingFrame(new CloseInfo(StatusCode.NORMAL).asFrame());

            assertThat(socket.capture.safePoll(), startsWith("onWebSocketConnect"));
            assertThat(socket.capture.safePoll(), startsWith("onWebSocketText(\"Hello World\")"));
            assertThat(socket.capture.safePoll(), startsWith("onWebSocketClose(1000,"));
        }
    }

    @Test
    public void testListenerPingPong(TestInfo testInfo) throws Exception
    {
        ListenerPingPongSocket socket = new ListenerPingPongSocket();
        EventDriver driver = wrap(socket);

        try (LocalWebSocketSession conn = new CloseableLocalWebSocketSession(container, testInfo.getDisplayName(), driver))
        {
            conn.start();
            conn.open();
            driver.incomingFrame(new PingFrame().setPayload("PING"));
            driver.incomingFrame(new PongFrame().setPayload("PONG"));
            driver.incomingFrame(new CloseInfo(StatusCode.NORMAL).asFrame());

            assertThat(socket.capture.safePoll(), startsWith("onWebSocketConnect"));
            assertThat(socket.capture.safePoll(), startsWith("onWebSocketPing("));
            assertThat(socket.capture.safePoll(), startsWith("onWebSocketPong("));
            assertThat(socket.capture.safePoll(), startsWith("onWebSocketClose(1000,"));
        }
    }

    @Test
    public void testListenerEmptyPingPong(TestInfo testInfo) throws Exception
    {
        ListenerPingPongSocket socket = new ListenerPingPongSocket();
        EventDriver driver = wrap(socket);

        try (LocalWebSocketSession conn = new CloseableLocalWebSocketSession(container, testInfo.getDisplayName(), driver))
        {
            conn.start();
            conn.open();
            driver.incomingFrame(new PingFrame());
            driver.incomingFrame(new PongFrame());
            driver.incomingFrame(new CloseInfo(StatusCode.NORMAL).asFrame());

            assertThat(socket.capture.safePoll(), startsWith("onWebSocketConnect"));
            assertThat(socket.capture.safePoll(), startsWith("onWebSocketPing("));
            assertThat(socket.capture.safePoll(), startsWith("onWebSocketPong("));
            assertThat(socket.capture.safePoll(), startsWith("onWebSocketClose(1000,"));
        }
    }

    private EventDriver wrap(Object websocket)
    {
        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        EventDriverFactory factory = new EventDriverFactory(new SimpleContainerScope(policy));
        return factory.wrap(websocket);
    }
}
