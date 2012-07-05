package org.eclipse.jetty.websocket.api;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.eclipse.jetty.websocket.frames.BinaryFrame;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.frames.DataFrame;
import org.eclipse.jetty.websocket.io.MessageInputStream;
import org.eclipse.jetty.websocket.io.MessageReader;
import org.eclipse.jetty.websocket.io.StreamAppender;
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
    private final Object websocket;
    private final WebSocketPolicy policy;
    private final EventMethods events;
    private final ByteBufferPool bufferPool;
    private WebSocketConnection connection;
    private ByteBuffer activeMessage;
    private StreamAppender activeStream;

    /**
     * Establish the driver for the Websocket POJO
     * 
     * @param websocket
     */
    public WebSocketEventDriver(Object websocket, EventMethodsCache methodsCache, WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        this.policy = policy;
        this.websocket = websocket;
        this.events = methodsCache.getMethods(websocket.getClass());
        this.bufferPool = bufferPool;

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

    private void appendBuffer(ByteBuffer msgBuf, ByteBuffer payloadBuf)
    {
        if (msgBuf.remaining() < payloadBuf.remaining())
        {
            throw new CloseException(StatusCode.MESSAGE_TOO_LARGE,"Message exceeded maximum buffer of " + msgBuf.limit());
        }
        BufferUtil.put(payloadBuf,msgBuf);
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
            // Work a Data Frame
            if (frame instanceof DataFrame)
            {
                DataFrame data = (DataFrame)frame;

                if ((events.onText == null) && (events.onBinary == null))
                {
                    // skip
                    return;
                }

                switch (data.getOpCode())
                {
                    case BINARY:
                    {
                        if (events.onBinary.isStreaming())
                        {
                            boolean needsNotification = false;

                            // Streaming Approach
                            if (activeStream == null)
                            {
                                // Allocate directly, not via ByteBufferPool, as this buffer
                                // is ultimately controlled by the end user, and we can't know
                                // when they are done using the stream in order to release any
                                // buffer allocated from the ByteBufferPool.
                                ByteBuffer buf = ByteBuffer.allocate(policy.getBufferSize());
                                this.activeStream = new MessageInputStream(buf);
                                needsNotification = true;
                            }

                            activeStream.appendBuffer(data.getPayload());

                            if (needsNotification)
                            {
                                events.onBinary.call(websocket,connection,activeStream);
                            }

                            if (data.isFin())
                            {
                                // close the stream.
                                activeStream.bufferComplete();
                                activeStream = null; // work with a new one
                            }
                        }
                        else
                        {
                            if (activeMessage == null)
                            {
                                // Acquire from ByteBufferPool is safe here, as the return
                                // from the notification is a good place to release the
                                // buffer.
                                activeMessage = bufferPool.acquire(policy.getBufferSize(),false);
                                BufferUtil.clearToFill(activeMessage);
                            }

                            appendBuffer(activeMessage,data.getPayload());

                            // normal case
                            if (frame.isFin())
                            {
                                // Notify using simple message approach.
                                try
                                {
                                    BufferUtil.flipToFlush(activeMessage,0);
                                    byte buf[] = BufferUtil.toArray(activeMessage);
                                    events.onBinary.call(websocket,connection,buf,0,buf.length);
                                }
                                finally
                                {
                                    bufferPool.release(activeMessage);
                                    activeMessage = null;
                                }
                            }

                        }
                        return;
                    }
                    case TEXT:
                    {
                        if (events.onText.isStreaming())
                        {
                            boolean needsNotification = false;

                            // Streaming Approach
                            if (activeStream == null)
                            {
                                // Allocate directly, not via ByteBufferPool, as this buffer
                                // is ultimately controlled by the end user, and we can't know
                                // when they are done using the stream in order to release any
                                // buffer allocated from the ByteBufferPool.
                                ByteBuffer buf = ByteBuffer.allocate(policy.getBufferSize());
                                this.activeStream = new MessageReader(buf);
                                needsNotification = true;
                            }

                            activeStream.appendBuffer(data.getPayload());

                            if (needsNotification)
                            {
                                events.onText.call(websocket,connection,activeStream);
                            }

                            if (data.isFin())
                            {
                                // close the stream.
                                activeStream.bufferComplete();
                                activeStream = null; // work with a new one
                            }
                        }
                        else
                        {
                            if (activeMessage == null)
                            {
                                // Acquire from ByteBufferPool is safe here, as the return
                                // from the notification is a good place to release the
                                // buffer.
                                activeMessage = bufferPool.acquire(policy.getBufferSize(),false);
                                BufferUtil.clearToFill(activeMessage);
                            }

                            appendBuffer(activeMessage,data.getPayload());

                            // normal case
                            if (frame.isFin())
                            {
                                // Notify using simple message approach.
                                try
                                {
                                    BufferUtil.flipToFlush(activeMessage,0);
                                    events.onText.call(websocket,connection,BufferUtil.toUTF8String(activeMessage));
                                }
                                finally
                                {
                                    bufferPool.release(activeMessage);
                                    activeMessage = null;
                                }
                            }
                        }
                        return;
                    }
                }
            }

            // Specified Binary Case
            if ((frame instanceof BinaryFrame) && (events.onBinary != null))
            {
                BinaryFrame bin = (BinaryFrame)frame;
                if (events.onBinary.isStreaming())
                {
                    // Streaming Approach
                }
                else
                {
                    // Byte array approach
                    byte buf[] = BufferUtil.toArray(bin.getPayload());
                    events.onBinary.call(websocket,connection,buf,0,buf.length);
                }
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
