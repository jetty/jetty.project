package org.eclipse.jetty.websocket.annotations;

import org.eclipse.jetty.websocket.api.LocalWebSocketConnection;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketEventDriver;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;
import org.junit.Test;

public class WebSocketAnnotationTest
{
    /**
     * Test Case for no exceptions
     */
    @Test
    public void testCapture()
    {
        WebSocketEventDriver driver = new WebSocketEventDriver(new NoopSocket());
        WebSocketConnection conn = new LocalWebSocketConnection();

        driver.setConnection(conn);
        driver.onConnect();
        driver.onFrame(new TextFrame("Hello World"));
        driver.onFrame(new CloseFrame(StatusCode.NORMAL));
        driver.onDisconnect();
    }

    /**
     * Test Case for no exceptions
     */
    @Test
    public void testNoop()
    {
        WebSocketEventDriver driver = new WebSocketEventDriver(new NoopSocket());
        WebSocketConnection conn = new LocalWebSocketConnection();

        driver.setConnection(conn);
        driver.onConnect();
        driver.onFrame(new TextFrame("Hello World"));
        driver.onFrame(new CloseFrame(StatusCode.NORMAL));
        driver.onDisconnect();
    }
}
