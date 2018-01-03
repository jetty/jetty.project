//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.frames.BinaryFrame;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.core.frames.DataFrame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.frames.PingFrame;
import org.eclipse.jetty.websocket.core.frames.PongFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;

/**
 * Base level implementation of local WebSocket Endpoint Frame handling.
 * <p>
 *    This implementation assumes RFC6455 behavior with HTTP/1.1.
 *    NOTE: The introduction of WebSocket over HTTP/2 might change the behavior and implementation some.
 * </p>
 */
public class AbstractFrameHandler implements FrameHandler
{
    private Logger LOG = Log.getLogger(AbstractFrameHandler.class);
    private byte partial = 0;
    private Utf8StringBuilder utf8;
    private ByteBuffer byteBuffer;
    private WebSocketChannel channel;

    @Override
    public void setWebSocketChannel(WebSocketChannel channel)
    {
        this.channel = channel;
    }
    
    public WebSocketChannel getWebSocketChannel()
    {
        return channel;
    }
    
    @Override
    public void onOpen()
    {
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        byte opcode = frame.getOpCode();
        if (LOG.isDebugEnabled())
            LOG.debug("{}: {}", OpCode.name(opcode),BufferUtil.toDetailString(frame.getPayload()));
        switch (opcode)
        {
            case OpCode.PING:
                onPing((PingFrame)frame,callback);
                break;
         
            case OpCode.PONG:
                onPong((PongFrame)frame,callback);
                break;
                
            case OpCode.TEXT:
                onText((TextFrame)frame,callback);
                break;
                
            case OpCode.BINARY:
                onBinary((BinaryFrame)frame,callback);
                break;
                
            case OpCode.CONTINUATION:
                onContinuation((ContinuationFrame)frame,callback);
                break;
                
            case OpCode.CLOSE:
                onClose((CloseFrame)frame,callback);
                break;
        }
    }

    @Override
    public void onError(Throwable cause)
    {
    }

    public void onPing(PingFrame frame, Callback callback)
    {
        ByteBuffer pongBuf;
        if (frame.hasPayload())
        {
            pongBuf = ByteBuffer.allocate(frame.getPayload().remaining());
            BufferUtil.put(frame.getPayload().slice(), pongBuf);
            BufferUtil.flipToFlush(pongBuf, 0);
        }
        else
        {
            pongBuf = ByteBuffer.allocate(0);
        }

        try
        {
            channel.outgoingFrame(new PongFrame().setPayload(pongBuf),callback,BatchMode.OFF);
        }
        catch (Throwable t)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to send pong", t);
            callback.failed(t);
        }
    }

    public void onPong(PongFrame frame, Callback callback)
    {
        callback.succeeded();
    }
    
    public void onText(DataFrame frame, Callback callback)
    {
        if (utf8==null)
            utf8 = new Utf8StringBuilder(Math.max(1024,frame.getPayloadLength()*2));
        else
            utf8.reset();

        if (frame.hasPayload())
            utf8.append(frame.getPayload());
        
        if (frame.isFin())
            utf8.checkState();
        else
            partial = frame.getOpCode();
        
        onText(utf8,callback,frame.isFin());            
    }

    public void onText(Utf8StringBuilder utf8, Callback callback, boolean fin)
    {
        callback.succeeded();
    }
    
    public void onBinary(DataFrame frame, Callback callback)
    {
        if (frame.isFin())
        {
            onBinary(frame.getPayload(),callback,true);
        }
        else
        {
            partial = frame.getOpCode();
            
            // TODO use the pool?
            if (byteBuffer==null)
                byteBuffer = BufferUtil.allocate(Math.max(1024,frame.getPayloadLength()*2));
            else
                BufferUtil.clear(byteBuffer);

            if (frame.hasPayload())
                BufferUtil.append(byteBuffer,frame.getPayload());
            
            onBinary(byteBuffer,callback,frame.isFin());
        }
    }

    public void onBinary(ByteBuffer payload, Callback callback, boolean fin)
    {
        callback.succeeded();
    }

    public void onContinuation(ContinuationFrame frame, Callback callback)
    {
        switch(partial)
        {
            case OpCode.TEXT:
                if (frame.hasPayload())
                    utf8.append(frame.getPayload());

                if (frame.isFin())
                    utf8.checkState();

                onText(frame, callback); // call continuation to appropriate "partial" path
                onText(utf8, callback, frame.isFin());
                break;

            case OpCode.BINARY:
                if (frame.hasPayload())
                {
                    int factor = frame.isFin()?1:3;
                    byteBuffer = BufferUtil.ensureCapacity(byteBuffer,byteBuffer.remaining()+factor*frame.getPayloadLength());
                    BufferUtil.append(byteBuffer,frame.getPayload());
                }

                onBinary(frame, callback); // call continuation to appropriate "partial" path
                onBinary(byteBuffer,callback,frame.isFin());
                break;
                
            default:
                callback.failed(new IllegalStateException());

        }
    }


    public void onClose(CloseFrame frame, Callback callback)
    {
        int respond;
        String reason=null;
        
        int code = frame.hasPayload() ? frame.getCloseStatus().getCode() : -1;
        
        switch(code)
        {
            case -1:
                respond = CloseStatus.NORMAL;
                break;
                
            case CloseStatus.NORMAL:
            case CloseStatus.SHUTDOWN:
            case CloseStatus.PROTOCOL:
            case CloseStatus.BAD_DATA:
            case CloseStatus.BAD_PAYLOAD:
            case CloseStatus.POLICY_VIOLATION:
            case CloseStatus.MESSAGE_TOO_LARGE:
            case CloseStatus.EXTENSION_ERROR:
            case CloseStatus.SERVER_ERROR:
                respond = 0;
                break;

            default:
                if (code>=3000 && code<=4999)
                {
                    respond = code;
                }
                else
                {
                    respond = WebSocketConstants.PROTOCOL;
                    reason = "invalid " + code + " close received";
                }
                break;
        }
        
        if (respond>0)
            channel.close(respond,reason,callback);
        else
            callback.succeeded();
    }

    @Override
    public void onClosed(CloseStatus closeStatus)
    {
    }
}
