package org.eclipse.jetty.websocket.api;

import java.io.IOException;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.eclipse.jetty.websocket.frames.BinaryFrame;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;
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
    private static final Logger LOG = Log.getLogger(WebSocketEventDriver.class);
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
        if (LOG.isDebugEnabled())
        {
            LOG.debug("{}.onConnect()",websocket.getClass().getSimpleName());
        }
        events.onConnect.call(websocket,connection);
    }

    /**
     * Internal entry point for incoming frames
     * 
     * @param frame
     *            the frame that appeared
     */
    @SuppressWarnings("unchecked")
    @Override
    public void onFrame(BaseFrame frame)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("{}.onFrame({})",websocket.getClass().getSimpleName(),frame);
        }

        // Generic Read-Only Frame version
        if ((frame instanceof Frame) && (events.onFrame != null))
        {
            events.onFrame.call(websocket,connection,frame);
            // DO NOT return; - as this is just a read-only notification.
        }

        // Specified Close Case
        if ((frame instanceof CloseFrame) && (events.onClose != null))
        {
            CloseFrame close = (CloseFrame)frame;
            events.onClose.call(websocket,connection,close.getStatusCode(),close.getReason());
            return;
        }

        try
        {
            // Specified Text Case
            if (frame instanceof TextFrame)
            {
                if (events.onText != null)
                {
                    TextFrame text = (TextFrame)frame;
                    events.onText.call(websocket,connection,text.getPayloadUTF8());
                    return;
                }

                // TODO
                // if (events.onTextStream != null)
                // {
                // }

                return;
            }

            // Specified Binary Case
            if (frame instanceof BinaryFrame)
            {
                if (events.onBinary != null)
                {
                    BinaryFrame bin = (BinaryFrame)frame;
                    // Byte array approach
                    byte buf[] = BufferUtil.toArray(bin.getPayload());
                    events.onBinary.call(websocket,connection,buf,0,buf.length);
                }

                // TODO
                // if (events.onBinaryStream != null)
                // {
                // }

                return;
            }
        }
        catch (Throwable t)
        {
            unhandled(t);
        }
    }

    @Override
    public void onWebSocketException(WebSocketException e)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("{}.onWebSocketException({})",websocket.getClass().getSimpleName(),e);
        }

        if (e instanceof CloseException)
        {
            CloseException close = (CloseException)e;
            terminateConnection(close.getStatusCode(),close.getMessage());
        }

        if (events.onException != null)
        {
            events.onException.call(websocket,connection,e);
        }
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

    private void terminateConnection(int statusCode, String rawreason)
    {
        try
        {
            String reason = rawreason;
            if (StringUtil.isNotBlank(reason))
            {
                // Trim big exception messages here.
                if (reason.length() > CloseFrame.MAX_REASON)
                {
                    reason = reason.substring(0,CloseFrame.MAX_REASON);
                }
            }
            LOG.debug("terminateConnection({},{})",statusCode,rawreason);
            connection.close(statusCode,reason);
        }
        catch (IOException e)
        {
            LOG.debug(e);
        }
    }

    private void unhandled(Throwable t)
    {
        LOG.warn("Unhandled Error (closing connection)",t);

        // Unhandled Error, close the connection.
        switch (policy.getBehavior())
        {
            case SERVER:
                terminateConnection(StatusCode.SERVER_ERROR,t.getClass().getSimpleName());
                break;
            case CLIENT:
                terminateConnection(StatusCode.POLICY_VIOLATION,t.getClass().getSimpleName());
                break;
        }
    }
}
