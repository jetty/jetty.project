//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.io.LocalWebSocketSession;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import examples.AdapterConnectCloseSocket;
import examples.AnnotatedBinaryArraySocket;
import examples.AnnotatedBinaryStreamSocket;
import examples.AnnotatedFramesSocket;
import examples.ListenerBasicSocket;

public class EventDriverTest
{
    @Rule
    public TestName testname = new TestName();

    private Frame makeBinaryFrame(String content, boolean fin)
    {
        return WebSocketFrame.binary().setFin(fin).setPayload(content);
    }

    @Test
    public void testAdapter_ConnectClose()
    {
        AdapterConnectCloseSocket socket = new AdapterConnectCloseSocket();
        EventDriver driver = wrap(socket);

        LocalWebSocketSession conn = new LocalWebSocketSession(testname,driver);
        conn.open();
        driver.incomingFrame(new CloseInfo(StatusCode.NORMAL).asFrame());

        socket.capture.assertEventCount(2);
        socket.capture.assertEventStartsWith(0,"onWebSocketConnect");
        socket.capture.assertEventStartsWith(1,"onWebSocketClose");
    }

    @Test
    public void testAnnotated_ByteArray()
    {
        AnnotatedBinaryArraySocket socket = new AnnotatedBinaryArraySocket();
        EventDriver driver = wrap(socket);

        LocalWebSocketSession conn = new LocalWebSocketSession(testname,driver);
        conn.open();
        driver.incomingFrame(makeBinaryFrame("Hello World",true));
        driver.incomingFrame(new CloseInfo(StatusCode.NORMAL).asFrame());

        socket.capture.assertEventCount(3);
        socket.capture.assertEventStartsWith(0,"onConnect");
        socket.capture.assertEvent(1,"onBinary([11],0,11)");
        socket.capture.assertEventStartsWith(2,"onClose(1000,");
    }

    @Test
    public void testAnnotated_Frames()
    {
        AnnotatedFramesSocket socket = new AnnotatedFramesSocket();
        EventDriver driver = wrap(socket);

        LocalWebSocketSession conn = new LocalWebSocketSession(testname,driver);
        conn.open();
        driver.incomingFrame(new WebSocketFrame(OpCode.PING).setPayload("PING"));
        driver.incomingFrame(WebSocketFrame.text("Text Me"));
        driver.incomingFrame(WebSocketFrame.binary().setPayload("Hello Bin"));
        driver.incomingFrame(new CloseInfo(StatusCode.SHUTDOWN).asFrame());

        socket.capture.assertEventCount(6);
        socket.capture.assertEventStartsWith(0,"onConnect(");
        socket.capture.assertEventStartsWith(1,"onFrame(PING[");
        socket.capture.assertEventStartsWith(2,"onFrame(TEXT[");
        socket.capture.assertEventStartsWith(3,"onFrame(BINARY[");
        socket.capture.assertEventStartsWith(4,"onFrame(CLOSE[");
        socket.capture.assertEventStartsWith(5,"onClose(1001,");
    }

    @Test
    public void testAnnotated_InputStream()
    {
        AnnotatedBinaryStreamSocket socket = new AnnotatedBinaryStreamSocket();
        EventDriver driver = wrap(socket);

        LocalWebSocketSession conn = new LocalWebSocketSession(testname,driver);
        conn.open();
        driver.incomingFrame(makeBinaryFrame("Hello World",true));
        driver.incomingFrame(new CloseInfo(StatusCode.NORMAL).asFrame());

        socket.capture.assertEventCount(3);
        socket.capture.assertEventStartsWith(0,"onConnect");
        socket.capture.assertEventRegex(1,"^onBinary\\(.*InputStream.*");
        socket.capture.assertEventStartsWith(2,"onClose(1000,");
    }

    @Test
    public void testListener_Text()
    {
        ListenerBasicSocket socket = new ListenerBasicSocket();
        EventDriver driver = wrap(socket);

        LocalWebSocketSession conn = new LocalWebSocketSession(testname,driver);
        conn.open();
        driver.incomingFrame(WebSocketFrame.text("Hello World"));
        driver.incomingFrame(new CloseInfo(StatusCode.NORMAL).asFrame());

        socket.capture.assertEventCount(3);
        socket.capture.assertEventStartsWith(0,"onWebSocketConnect");
        socket.capture.assertEventStartsWith(1,"onWebSocketText(\"Hello World\")");
        socket.capture.assertEventStartsWith(2,"onWebSocketClose(1000,");
    }

    private EventDriver wrap(Object websocket)
    {
        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        EventDriverFactory factory = new EventDriverFactory(policy);
        return factory.wrap(websocket);
    }
}
