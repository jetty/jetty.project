package org.eclipse.jetty.websocket.api;

import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.frames.BaseFrame;

/**
 * Responsible for routing the internally generated events destined for a specific WebSocket instance to whatever choice of development style the developer has
 * used to wireup their specific WebSocket implementation.
 * <p>
 * Supports WebSocket instances that either implement {@link WebSocketListener} or have used the {@link WebSocket &#064;WebSocket} annotation.
 * <p>
 * There will be an instance of the WebSocketEventDriver per connection.
 */
public class WebSocketEventDriver
{
    private Object websocket;
    private WebSocketConnection connection;

    /**
     * Establish the driver for the Websocket POJO
     * 
     * @param websocket
     */
    public WebSocketEventDriver(Object websocket)
    {
        this.websocket = websocket;
        // TODO Discover and bind what routing is available in the POJO
    }

    /**
     * Get the Websocket POJO in use
     * 
     * @return the Websocket POJO
     */
    public Object getWebSocketObject()
    {
        return websocket;
    }

    /**
     * Internal entry point for connection established
     */
    public void onConnect()
    {
        // TODO Auto-generated method stub
    }

    /**
     * Internal entry point for connection disconnected
     */
    public void onDisconnect()
    {
        // TODO Auto-generated method stub
    }

    /**
     * Internal entry point for incoming frames
     * 
     * @param frame
     *            the frame that appeared
     */
    public void onFrame(BaseFrame frame)
    {
        // TODO Auto-generated method stub
    }

    /**
     * Set the connection to use for this driver
     * 
     * @param conn
     *            the connection
     */
    public void setConnection(WebSocketConnection conn)
    {
        this.connection = conn;
    }
}
