package org.eclipse.jetty.websocket.api;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.samples.AdapterConnectCloseSocket;
import org.eclipse.jetty.websocket.api.samples.AnnotatedBinaryArraySocket;
import org.eclipse.jetty.websocket.api.samples.AnnotatedBinaryStreamSocket;
import org.eclipse.jetty.websocket.api.samples.AnnotatedFramesSocket;
import org.eclipse.jetty.websocket.api.samples.ListenerBasicSocket;
import org.eclipse.jetty.websocket.frames.BinaryFrame;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.frames.PingFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class WebSocketEventDriverTest
{
    @Rule
    public TestName testname = new TestName();

    private BinaryFrame makeBinaryFrame(String content, boolean fin)
    {
        BinaryFrame bin = new BinaryFrame(content.getBytes(StringUtil.__UTF8_CHARSET));
        bin.setFin(fin);
        return bin;
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

        LocalWebSocketConnection conn = new LocalWebSocketConnection(testname);
        driver.setConnection(conn);
        driver.onConnect();
        driver.onFrame(new CloseFrame(StatusCode.NORMAL));

        socket.capture.assertEventCount(2);
        socket.capture.assertEventStartsWith(0,"onWebSocketConnect");
        socket.capture.assertEventStartsWith(1,"onWebSocketClose");
    }

    @Test
    public void testAnnotated_ByteArray()
    {
        AnnotatedBinaryArraySocket socket = new AnnotatedBinaryArraySocket();
        WebSocketEventDriver driver = newDriver(socket);

        LocalWebSocketConnection conn = new LocalWebSocketConnection(testname);
        driver.setConnection(conn);
        driver.onConnect();
        driver.onFrame(makeBinaryFrame("Hello World",true));
        driver.onFrame(new CloseFrame(StatusCode.NORMAL));

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

        LocalWebSocketConnection conn = new LocalWebSocketConnection(testname);
        driver.setConnection(conn);
        driver.onConnect();
        driver.onFrame(new PingFrame(StringUtil.getUtf8Bytes("PING")));
        driver.onFrame(new TextFrame("Text Me"));
        driver.onFrame(new BinaryFrame(StringUtil.getUtf8Bytes("Hello Bin")));
        driver.onFrame(new CloseFrame(StatusCode.SHUTDOWN));

        socket.capture.assertEventCount(6);
        socket.capture.assertEventStartsWith(0,"onConnect(");
        socket.capture.assertEventStartsWith(1,"onFrame(Ping");
        socket.capture.assertEventStartsWith(2,"onFrame(Text");
        socket.capture.assertEventStartsWith(3,"onFrame(Binary");
        socket.capture.assertEventStartsWith(4,"onFrame(Close");
        socket.capture.assertEventStartsWith(5,"onClose(1001,");
    }

    @Test
    public void testAnnotated_InputStream()
    {
        AnnotatedBinaryStreamSocket socket = new AnnotatedBinaryStreamSocket();
        WebSocketEventDriver driver = newDriver(socket);

        LocalWebSocketConnection conn = new LocalWebSocketConnection(testname);
        driver.setConnection(conn);
        driver.onConnect();
        driver.onFrame(makeBinaryFrame("Hello World",true));
        driver.onFrame(new CloseFrame(StatusCode.NORMAL));

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

        LocalWebSocketConnection conn = new LocalWebSocketConnection(testname);
        driver.setConnection(conn);
        driver.onConnect();
        driver.onFrame(new TextFrame("Hello World"));
        driver.onFrame(new CloseFrame(StatusCode.NORMAL));

        socket.capture.assertEventCount(3);
        socket.capture.assertEventStartsWith(0,"onWebSocketConnect");
        socket.capture.assertEventStartsWith(1,"onWebSocketText(\"Hello World\")");
        socket.capture.assertEventStartsWith(2,"onWebSocketClose(1000,");
    }
}
