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

package org.eclipse.jetty.websocket.driver;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.examples.AdapterConnectCloseSocket;
import org.eclipse.jetty.websocket.examples.AnnotatedBinaryArraySocket;
import org.eclipse.jetty.websocket.examples.AnnotatedBinaryStreamSocket;
import org.eclipse.jetty.websocket.examples.AnnotatedFramesSocket;
import org.eclipse.jetty.websocket.examples.ListenerBasicSocket;
import org.eclipse.jetty.websocket.io.LocalWebSocketSession;
import org.eclipse.jetty.websocket.protocol.CloseInfo;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class WebSocketEventDriverTest
{
    @Rule
    public TestName testname = new TestName();

    private WebSocketFrame makeBinaryFrame(String content, boolean fin)
    {
        return WebSocketFrame.binary().setFin(fin).setPayload(content);
    }

    private WebSocketEventDriver newDriver(Object websocket)
    {
        EventMethodsCache methodsCache = new EventMethodsCache();
        methodsCache.register(websocket.getClass());
        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        ByteBufferPool bufferPool = new StandardByteBufferPool();
        return new WebSocketEventDriver(websocket,methodsCache,policy,bufferPool);
    }

    @Test
    public void testAdapter_ConnectClose()
    {
        AdapterConnectCloseSocket socket = new AdapterConnectCloseSocket();
        WebSocketEventDriver driver = newDriver(socket);

        LocalWebSocketSession conn = new LocalWebSocketSession(testname);
        driver.setSession(conn);
        driver.onConnect();
        driver.incoming(new CloseInfo(StatusCode.NORMAL).asFrame());

        socket.capture.assertEventCount(2);
        socket.capture.assertEventStartsWith(0,"onWebSocketConnect");
        socket.capture.assertEventStartsWith(1,"onWebSocketClose");
    }

    @Test
    public void testAnnotated_ByteArray()
    {
        AnnotatedBinaryArraySocket socket = new AnnotatedBinaryArraySocket();
        WebSocketEventDriver driver = newDriver(socket);

        LocalWebSocketSession conn = new LocalWebSocketSession(testname);
        driver.setSession(conn);
        driver.onConnect();
        driver.incoming(makeBinaryFrame("Hello World",true));
        driver.incoming(new CloseInfo(StatusCode.NORMAL).asFrame());

        socket.capture.assertEventCount(3);
        socket.capture.assertEventStartsWith(0,"onConnect");
        socket.capture.assertEvent(1,"onBinary([11],0,11)");
        socket.capture.assertEventStartsWith(2,"onClose(1000,");
    }

    @Test
    public void testAnnotated_Frames()
    {
        AnnotatedFramesSocket socket = new AnnotatedFramesSocket();
        WebSocketEventDriver driver = newDriver(socket);

        LocalWebSocketSession conn = new LocalWebSocketSession(testname);
        driver.setSession(conn);
        driver.onConnect();
        driver.incoming(new WebSocketFrame(OpCode.PING).setPayload("PING"));
        driver.incoming(WebSocketFrame.text("Text Me"));
        driver.incoming(WebSocketFrame.binary().setPayload("Hello Bin"));
        driver.incoming(new CloseInfo(StatusCode.SHUTDOWN).asFrame());

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
        WebSocketEventDriver driver = newDriver(socket);

        LocalWebSocketSession conn = new LocalWebSocketSession(testname);
        driver.setSession(conn);
        driver.onConnect();
        driver.incoming(makeBinaryFrame("Hello World",true));
        driver.incoming(new CloseInfo(StatusCode.NORMAL).asFrame());

        socket.capture.assertEventCount(3);
        socket.capture.assertEventStartsWith(0,"onConnect");
        socket.capture.assertEventRegex(1,"^onBinary\\(.*InputStream.*");
        socket.capture.assertEventStartsWith(2,"onClose(1000,");
    }

    @Test
    public void testListener_Text()
    {
        ListenerBasicSocket socket = new ListenerBasicSocket();
        WebSocketEventDriver driver = newDriver(socket);

        LocalWebSocketSession conn = new LocalWebSocketSession(testname);
        driver.setSession(conn);
        driver.onConnect();
        driver.incoming(WebSocketFrame.text("Hello World"));
        driver.incoming(new CloseInfo(StatusCode.NORMAL).asFrame());

        socket.capture.assertEventCount(3);
        socket.capture.assertEventStartsWith(0,"onWebSocketConnect");
        socket.capture.assertEventStartsWith(1,"onWebSocketText(\"Hello World\")");
        socket.capture.assertEventStartsWith(2,"onWebSocketClose(1000,");
    }
}
