package org.eclipse.jetty.websocket.api;

import org.eclipse.jetty.websocket.annotations.EventMethods;
import org.eclipse.jetty.websocket.annotations.EventMethodsCache;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.eclipse.jetty.websocket.parser.Parser;

/**
 * Responsible for routing the internally generated events destined for a specific WebSocket instance to whatever choice of development style the developer has
 * used to wireup their specific WebSocket implementation.
 * <p>
 * Supports WebSocket instances that either implement {@link WebSocketListener} or have used the {@link WebSocket &#064;WebSocket} annotation.
 * <p>
 * There will be an instance of the WebSocketEventDriver per connection.
 */
public class WebSocketEventDriver implements Parser.Listener
{
    private Object websocket;
    private WebSocketPolicy policy;
    private WebSocketConnection connection;
    private EventMethods events;

    /**
     * Establish the driver for the Websocket POJO
     * 
     * @param websocket
     */
    public WebSocketEventDriver(EventMethodsCache methodsCache, WebSocketPolicy policy, Object websocket)
    {
        this.policy = policy;
        this.websocket = websocket;
        this.events = methodsCache.getMethods(websocket.getClass());

        if (events.isAnnotated())
        {
            WebSocket anno = websocket.getClass().getAnnotation(WebSocket.class);
            // Setup the policy
            policy.setBufferSize(anno.maxBufferSize());
            policy.setMaxBinaryMessageSize(anno.maxBinarySize());
            policy.setMaxTextMessageSize(anno.maxTextSize());
            policy.setMaxIdleTime(anno.maxIdleTime());
        }
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
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
        events.onConnect.call(websocket,connection);
    }

    /**
     * Internal entry point for incoming frames
     * 
     * @param frame
     *            the frame that appeared
     */
    @Override
    public void onFrame(BaseFrame frame)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void onWebSocketException(WebSocketException e)
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
