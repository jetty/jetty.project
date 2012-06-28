package org.eclipse.jetty.websocket.api;

import org.eclipse.jetty.websocket.api.samples.AdapterConnectCloseSocket;
import org.eclipse.jetty.websocket.api.samples.ListenerBasicSocket;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class WebSocketEventDriverTest
{
    @Rule
    public TestName testname = new TestName();

    private WebSocketEventDriver newDriver(Object websocket)
    {
        EventMethodsCache methodsCache = new EventMethodsCache();
        methodsCache.register(websocket.getClass());
        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        return new WebSocketEventDriver(methodsCache,policy,websocket);
    }

    @Test
    public void testAdapterConnectClose()
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
    public void testListenerBasic()
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
