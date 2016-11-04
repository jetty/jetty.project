//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.util.concurrent.TimeoutException;

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
import org.eclipse.jetty.websocket.common.test.LeakTrackingBufferPoolRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import examples.AdapterConnectCloseSocket;
import examples.AnnotatedBinaryArraySocket;
import examples.AnnotatedBinaryStreamSocket;
import examples.AnnotatedFramesSocket;
import examples.AnnotatedTextSocket;
import examples.ListenerBasicSocket;
import examples.ListenerPingPongSocket;

public class EventDriverTest
{
    @Rule
    public TestName testname = new TestName();

    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule("Test");
    
    private WebSocketContainerScope container;
    
    @Before
    public void initContainer()
    {
        this.container = new SimpleContainerScope(WebSocketPolicy.newClientPolicy());
    }

    private Frame makeBinaryFrame(String content, boolean fin)
    {
        return new BinaryFrame().setPayload(content).setFin(fin);
    }

    @Test
    public void testAdapter_ConnectClose() throws Exception
    {
        AdapterConnectCloseSocket socket = new AdapterConnectCloseSocket();
        EventDriver driver = wrap(socket);

        try (LocalWebSocketSession conn = new CloseableLocalWebSocketSession(container,testname,driver))
        {
            conn.open();
            driver.incomingFrame(new CloseInfo(StatusCode.NORMAL).asFrame());

            socket.capture.assertEventCount(2);
            socket.capture.pop().assertEventStartsWith("onWebSocketConnect");
            socket.capture.pop().assertEventStartsWith("onWebSocketClose");
        }
    }

    @Test
    public void testAnnotated_ByteArray() throws Exception
    {
        AnnotatedBinaryArraySocket socket = new AnnotatedBinaryArraySocket();
        EventDriver driver = wrap(socket);

        try (LocalWebSocketSession conn = new CloseableLocalWebSocketSession(container,testname,driver))
        {
            conn.open();
            driver.incomingFrame(makeBinaryFrame("Hello World",true));
            driver.incomingFrame(new CloseInfo(StatusCode.NORMAL).asFrame());

            socket.capture.assertEventCount(3);
            socket.capture.pop().assertEventStartsWith("onConnect");
            socket.capture.pop().assertEvent("onBinary([11],0,11)");
            socket.capture.pop().assertEventStartsWith("onClose(1000,");
        }
    }

    @Test
    public void testAnnotated_Error() throws Exception
    {
        AnnotatedTextSocket socket = new AnnotatedTextSocket();
        EventDriver driver = wrap(socket);

        try (LocalWebSocketSession conn = new CloseableLocalWebSocketSession(container,testname,driver))
        {
            conn.open();
            driver.incomingError(new WebSocketException("oof"));
            driver.incomingFrame(new CloseInfo(StatusCode.NORMAL).asFrame());

            socket.capture.assertEventCount(3);
            socket.capture.pop().assertEventStartsWith("onConnect");
            socket.capture.pop().assertEventStartsWith("onError(WebSocketException: oof)");
            socket.capture.pop().assertEventStartsWith("onClose(1000,");
        }
    }

    @Test
    public void testAnnotated_Frames() throws Exception
    {
        AnnotatedFramesSocket socket = new AnnotatedFramesSocket();
        EventDriver driver = wrap(socket);

        try (LocalWebSocketSession conn = new CloseableLocalWebSocketSession(container,testname,driver))
        {
            conn.open();
            driver.incomingFrame(new PingFrame().setPayload("PING"));
            driver.incomingFrame(new TextFrame().setPayload("Text Me"));
            driver.incomingFrame(new BinaryFrame().setPayload("Hello Bin"));
            driver.incomingFrame(new CloseInfo(StatusCode.SHUTDOWN,"testcase").asFrame());

            socket.capture.assertEventCount(6);
            socket.capture.pop().assertEventStartsWith("onConnect(");
            socket.capture.pop().assertEventStartsWith("onFrame(PING[");
            socket.capture.pop().assertEventStartsWith("onFrame(TEXT[");
            socket.capture.pop().assertEventStartsWith("onFrame(BINARY[");
            socket.capture.pop().assertEventStartsWith("onFrame(CLOSE[");
            socket.capture.pop().assertEventStartsWith("onClose(1001,");
        }
    }

    @Test
    public void testAnnotated_InputStream() throws IOException, TimeoutException, InterruptedException
    {
        AnnotatedBinaryStreamSocket socket = new AnnotatedBinaryStreamSocket();
        EventDriver driver = wrap(socket);

        try (LocalWebSocketSession conn = new CloseableLocalWebSocketSession(container,testname,driver))
        {
            conn.open();
            driver.incomingFrame(makeBinaryFrame("Hello World",true));
            driver.incomingFrame(new CloseInfo(StatusCode.NORMAL).asFrame());

            socket.capture.assertEventCount(3);
            socket.capture.pop().assertEventStartsWith("onConnect");
            socket.capture.pop().assertEventRegex("^onBinary\\(.*InputStream.*");
            socket.capture.pop().assertEventStartsWith("onClose(1000,");
        }
    }

    @Test
    public void testListenerBasic_Text() throws Exception
    {
        ListenerBasicSocket socket = new ListenerBasicSocket();
        EventDriver driver = wrap(socket);

        try (LocalWebSocketSession conn = new CloseableLocalWebSocketSession(container,testname,driver))
        {
            conn.start();
            conn.open();
            driver.incomingFrame(new TextFrame().setPayload("Hello World"));
            driver.incomingFrame(new CloseInfo(StatusCode.NORMAL).asFrame());

            socket.capture.assertEventCount(3);
            socket.capture.pop().assertEventStartsWith("onWebSocketConnect");
            socket.capture.pop().assertEventStartsWith("onWebSocketText(\"Hello World\")");
            socket.capture.pop().assertEventStartsWith("onWebSocketClose(1000,");
        }
    }

    @Test
    public void testListenerPingPong() throws Exception
    {
        ListenerPingPongSocket socket = new ListenerPingPongSocket();
        EventDriver driver = wrap(socket);

        try (LocalWebSocketSession conn = new CloseableLocalWebSocketSession(container,testname,driver))
        {
            conn.start();
            conn.open();
            driver.incomingFrame(new PingFrame().setPayload("PING"));
            driver.incomingFrame(new PongFrame().setPayload("PONG"));
            driver.incomingFrame(new CloseInfo(StatusCode.NORMAL).asFrame());

            socket.capture.assertEventCount(4);
            socket.capture.pop().assertEventStartsWith("onWebSocketConnect");
            socket.capture.pop().assertEventStartsWith("onWebSocketPing(");
            socket.capture.pop().assertEventStartsWith("onWebSocketPong(");
            socket.capture.pop().assertEventStartsWith("onWebSocketClose(1000,");
        }
    }

    @Test
    public void testListenerEmptyPingPong() throws Exception
    {
        ListenerPingPongSocket socket = new ListenerPingPongSocket();
        EventDriver driver = wrap(socket);

        try (LocalWebSocketSession conn = new CloseableLocalWebSocketSession(container,testname,driver))
        {
            conn.start();
            conn.open();
            driver.incomingFrame(new PingFrame());
            driver.incomingFrame(new PongFrame());
            driver.incomingFrame(new CloseInfo(StatusCode.NORMAL).asFrame());

            socket.capture.assertEventCount(4);
            socket.capture.pop().assertEventStartsWith("onWebSocketConnect");
            socket.capture.pop().assertEventStartsWith("onWebSocketPing(");
            socket.capture.pop().assertEventStartsWith("onWebSocketPong(");
            socket.capture.pop().assertEventStartsWith("onWebSocketClose(1000,");
        }
    }

    private EventDriver wrap(Object websocket)
    {
        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        EventDriverFactory factory = new EventDriverFactory(policy);
        return factory.wrap(websocket);
    }
}
