//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.frames.BinaryFrame;
import org.eclipse.jetty.websocket.common.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.common.frames.DataFrame;
import org.eclipse.jetty.websocket.common.frames.PingFrame;
import org.eclipse.jetty.websocket.common.frames.PongFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.io.FutureWriteCallback;

/**
 * Endpoint for Writing messages to the Remote websocket.
 */
public class WebSocketRemoteEndpoint implements RemoteEndpoint
{
    /** JSR-356 blocking send behavior message */
    private static final String PRIORMSG_ERROR = "Prior message pending, cannot start new message yet.";
    /** Type of Message */
    private enum MsgType {NONE,TEXT,BINARY,PARTIAL_TEXT,PARTIAL_BINARY};
    
    private static final WriteCallback NOOP_CALLBACK = new WriteCallback()
    {
        @Override
        public void writeSuccess()
        {
        }
        
        @Override
        public void writeFailed(Throwable x)
        {
        }
    };

    private static final Logger LOG = Log.getLogger(WebSocketRemoteEndpoint.class);
    public final LogicalConnection connection;
    public final OutgoingFrames outgoing;
    /** JSR-356 blocking send behaviour message and Type sanity to support partial send properly */
    private final AtomicReference<MsgType> msgType = new AtomicReference<>(MsgType.NONE);
    private final BlockingWriteCallback blocker = new BlockingWriteCallback();

    public WebSocketRemoteEndpoint(LogicalConnection connection, OutgoingFrames outgoing)
    {
        if (connection == null)
        {
            throw new IllegalArgumentException("LogicalConnection cannot be null");
        }
        this.connection = connection;
        this.outgoing = outgoing;
    }

    private void blockingWrite(WebSocketFrame frame) throws IOException
    {
        sendFrame(frame,blocker);
        blocker.block();
    }

    private void lockMsg(MsgType type)
    {
        while(true)
        {
            MsgType was=msgType.get();
            switch (was)
            {
                case NONE:
                    if (msgType.compareAndSet(MsgType.NONE,type))
                        return;
                    break;
                    
                case BINARY:
                case TEXT:
                case PARTIAL_BINARY:
                case PARTIAL_TEXT:
                    throw new IllegalStateException(PRIORMSG_ERROR);
            }
        }
    }

    private boolean lockPartialMsg(MsgType type)
    {
        while(true)
        {
            MsgType was=msgType.get();
            switch (was)
            {
                case NONE:
                    if (msgType.compareAndSet(MsgType.NONE,type))
                        return true;
                    break;
                    
                case BINARY:
                case TEXT:
                    throw new IllegalStateException(PRIORMSG_ERROR);
                    
                case PARTIAL_BINARY:
                    if (type==MsgType.BINARY && msgType.compareAndSet(MsgType.PARTIAL_BINARY,MsgType.BINARY))
                        return false;
                    throw new IllegalStateException("Prior BINARY message pending, cannot start new "+type+" message yet.");
                    
                case PARTIAL_TEXT:
                    if (type==MsgType.TEXT && msgType.compareAndSet(MsgType.PARTIAL_TEXT,MsgType.TEXT))
                        return false;
                    throw new IllegalStateException("Prior TEXT message pending, cannot start new "+type+" message yet.");
            }
        }
    }
    
    private void unlockMsg()
    {
        MsgType was=msgType.get();
        switch (was)
        {
            case NONE:
                throw new IllegalStateException("not locked");
                
            case PARTIAL_BINARY:
            case PARTIAL_TEXT:
                throw new IllegalStateException("in partial");
                
            default:
                if (!msgType.compareAndSet(was,MsgType.NONE))
                    throw new IllegalStateException("concurrent unlock");
        }
    }
    
    private void unlockPartialMsg()
    {
        MsgType was=msgType.get();
        switch (was)
        {
            case NONE:
                throw new IllegalStateException("not locked");
                
            case PARTIAL_BINARY:
            case PARTIAL_TEXT:
                throw new IllegalStateException("in partial");
                
            case BINARY:
                if (!msgType.compareAndSet(was,MsgType.PARTIAL_BINARY))
                    throw new IllegalStateException("concurrent unlock");
                return;
            case TEXT:
                if (!msgType.compareAndSet(was,MsgType.PARTIAL_TEXT))
                    throw new IllegalStateException("concurrent unlock");
                return;
        }
    }
    
    public InetSocketAddress getInetSocketAddress()
    {
        return connection.getRemoteAddress();
    }

    /**
     * Internal
     * 
     * @param frame
     *            the frame to write
     * @return the future for the network write of the frame
     */
    private Future<Void> sendAsyncFrame(WebSocketFrame frame)
    {
        FutureWriteCallback future = new FutureWriteCallback();
        sendFrame(frame,future);
        return future;
    }

    /**
     * Blocking write of bytes.
     */
    @Override
    public void sendBytes(ByteBuffer data) throws IOException
    {
        lockMsg(MsgType.BINARY);
        try
        {
            connection.getIOState().assertOutputOpen();
            if (LOG.isDebugEnabled())
            {
                LOG.debug("sendBytes with {}",BufferUtil.toDetailString(data));
            }
            blockingWrite(new BinaryFrame().setPayload(data));
        }
        finally
        {
            unlockMsg();
        }
    }

    @Override
    public Future<Void> sendBytesByFuture(ByteBuffer data)
    {
        lockMsg(MsgType.BINARY);
        try
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("sendBytesByFuture with {}",BufferUtil.toDetailString(data));
            }
            return sendAsyncFrame(new BinaryFrame().setPayload(data));
        }
        finally
        {
            unlockMsg();
        }
    }

    @Override
    public void sendBytes(ByteBuffer data, WriteCallback callback)
    {
        lockMsg(MsgType.BINARY);
        try
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("sendBytes({}, {})",BufferUtil.toDetailString(data),callback);
            }
            sendFrame(new BinaryFrame().setPayload(data),callback==null?NOOP_CALLBACK:callback);
        }
        finally
        {
            unlockMsg();
        }
    }

    public void sendFrame(WebSocketFrame frame, WriteCallback callback)
    {
        try
        {
            connection.getIOState().assertOutputOpen();
            outgoing.outgoingFrame(frame,callback);
        }
        catch (IOException e)
        {
            callback.writeFailed(e);
        }
    }

    @Override
    public void sendPartialBytes(ByteBuffer fragment, boolean isLast) throws IOException
    {
        boolean first=lockPartialMsg(MsgType.TEXT);
        try
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("sendPartialBytes({}, {})",BufferUtil.toDetailString(fragment),isLast);
            }
            DataFrame frame = first?new BinaryFrame():new ContinuationFrame();
            frame.setPayload(fragment);
            frame.setFin(isLast);
            blockingWrite(frame);
        }
        finally
        {
            if (isLast)
                unlockMsg();
            else
                unlockPartialMsg();
        }
    }

    @Override
    public void sendPartialString(String fragment, boolean isLast) throws IOException
    {
        boolean first=lockPartialMsg(MsgType.BINARY);
        try
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("sendPartialString({}, {})",fragment,isLast);
            }
            DataFrame frame = first?new TextFrame():new ContinuationFrame();
            frame.setPayload(BufferUtil.toBuffer(fragment,StandardCharsets.UTF_8));
            frame.setFin(isLast);
            blockingWrite(frame);
        }
        finally
        {
            if (isLast)
                unlockMsg();
            else
                unlockPartialMsg();
        }
    }

    @Override
    public void sendPing(ByteBuffer applicationData) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendPing with {}",BufferUtil.toDetailString(applicationData));
        }
        sendAsyncFrame(new PingFrame().setPayload(applicationData));
    }

    @Override
    public void sendPong(ByteBuffer applicationData) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendPong with {}",BufferUtil.toDetailString(applicationData));
        }
        sendAsyncFrame(new PongFrame().setPayload(applicationData));
    }

    @Override
    public void sendString(String text) throws IOException
    {
        lockMsg(MsgType.TEXT);
        try
        {
            WebSocketFrame frame = new TextFrame().setPayload(text);
            if (LOG.isDebugEnabled())
            {
                LOG.debug("sendString with {}",BufferUtil.toDetailString(frame.getPayload()));
            }
            blockingWrite(frame);
        }
        finally
        {
            unlockMsg();
        }
    }

    @Override
    public Future<Void> sendStringByFuture(String text)
    {
        lockMsg(MsgType.TEXT);
        try
        {
            TextFrame frame = new TextFrame().setPayload(text);
            if (LOG.isDebugEnabled())
            {
                LOG.debug("sendStringByFuture with {}",BufferUtil.toDetailString(frame.getPayload()));
            }
            return sendAsyncFrame(frame);  
        }
        finally
        {
            unlockMsg();
        }
    }

    @Override
    public void sendString(String text, WriteCallback callback)
    {
        lockMsg(MsgType.TEXT);
        try
        {
            TextFrame frame = new TextFrame().setPayload(text);
            if (LOG.isDebugEnabled())
            {
                LOG.debug("sendString({},{})",BufferUtil.toDetailString(frame.getPayload()),callback);
            }
            sendFrame(frame,callback==null?NOOP_CALLBACK:callback);
        }
        finally
        {
            unlockMsg();
        }
    }
}
