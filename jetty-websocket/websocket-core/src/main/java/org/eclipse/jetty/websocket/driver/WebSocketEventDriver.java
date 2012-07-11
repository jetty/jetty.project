// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.driver;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Utf8Appendable.NotUtf8Exception;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.CloseException;
import org.eclipse.jetty.websocket.api.MessageTooLargeException;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.io.MessageInputStream;
import org.eclipse.jetty.websocket.io.MessageReader;
import org.eclipse.jetty.websocket.io.RawConnection;
import org.eclipse.jetty.websocket.io.StreamAppender;
import org.eclipse.jetty.websocket.protocol.CloseInfo;
import org.eclipse.jetty.websocket.protocol.Frame;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.Parser;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

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
    private RawConnection connection;
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

    private void appendBuffer(ByteBuffer msgBuf, ByteBuffer byteBuffer)
    {
        if (msgBuf.remaining() < byteBuffer.remaining())
        {
            throw new MessageTooLargeException("Message exceeded maximum buffer");
        }
        msgBuf.put(byteBuffer);
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
    public void onFrame(WebSocketFrame frame)
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

        try
        {
            switch (frame.getOpCode())
            {
                case CLOSE:
                {
                    CloseInfo close = new CloseInfo(frame);
                    if (events.onClose != null)
                    {
                        events.onClose.call(websocket,connection,close.getStatusCode(),close.getReason());
                    }
                    throw new CloseException(close.getStatusCode(),close.getReason());
                }
                case PONG:
                {
                    WebSocketFrame pong = new WebSocketFrame(OpCode.PONG);
                    pong.setPayload(frame.getPayload());
                    connection.write(null,new FutureCallback<Void>(),pong);
                    break;
                }
                case BINARY:
                {
                    if (events.onBinary == null)
                    {
                        // not interested in binary events
                        return;
                    }
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

                        activeStream.appendBuffer(frame.getPayload());

                        if (needsNotification)
                        {
                            events.onBinary.call(websocket,connection,activeStream);
                        }

                        if (frame.isFin())
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

                        appendBuffer(activeMessage,frame.getPayload());

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
                    if (events.onText == null)
                    {
                        // not interested in text events
                        return;
                    }
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

                        activeStream.appendBuffer(frame.getPayload());

                        if (needsNotification)
                        {
                            events.onText.call(websocket,connection,activeStream);
                        }

                        if (frame.isFin())
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

                        appendBuffer(activeMessage,frame.getPayload());

                        // normal case
                        if (frame.isFin())
                        {
                            // Notify using simple message approach.
                            try
                            {
                                BufferUtil.flipToFlush(activeMessage,0);
                                byte data[] = BufferUtil.toArray(activeMessage);
                                Utf8StringBuilder utf = new Utf8StringBuilder();
                                // TODO: FIX EVIL COPY
                                utf.append(data,0,data.length);

                                events.onText.call(websocket,connection,utf.toString());
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
        catch (NotUtf8Exception e)
        {
            terminateConnection(StatusCode.BAD_PAYLOAD,e.getMessage());
        }
        catch (CloseException e)
        {
            terminateConnection(e.getStatusCode(),e.getMessage());
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
    public void setConnection(RawConnection conn)
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
                if (reason.length() > (WebSocketFrame.MAX_CONTROL_PAYLOAD - 2))
                {
                    reason = reason.substring(0,WebSocketFrame.MAX_CONTROL_PAYLOAD - 2);
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
