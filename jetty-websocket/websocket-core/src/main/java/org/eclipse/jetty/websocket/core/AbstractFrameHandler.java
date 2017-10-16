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
import org.eclipse.jetty.util.Utf8StringBuffer;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.frames.PongFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;

public class AbstractFrameHandler implements FrameHandler
{
    private Logger LOG = Log.getLogger(this.getClass());
    private byte partial = 0;
    private Utf8StringBuffer utf8Buffer;
    private ByteBuffer byteBuffer;

    @Override
    public void onOpen(WebSocketChannel channel)
    {
    }

    @Override
    public void onFrame(WebSocketChannel channel, Frame frame, Callback callback)
    {
        byte opcode = frame.getOpCode();
        if (LOG.isDebugEnabled())
            LOG.debug("{}: {}", OpCode.name(opcode),BufferUtil.toDetailString(frame.getPayload()));
        switch (opcode)
        {
            case OpCode.PING:
                onPing(channel,frame,callback);
                break;
         
            case OpCode.PONG:
                onPing(channel,frame,callback);
                break;
                
            case OpCode.TEXT:
                if (frame.isFin())
                    onText(channel,((TextFrame)frame).getPayloadAsUTF8(),callback);
                else
                    onPartialText(channel,frame,callback);
                break;
                
            case OpCode.BINARY:

                if (frame.isFin())
                    onBinary(channel,frame.getPayload(),callback);
                else
                    onPartialBinary(channel,frame,callback);
                break;
                
                
            case OpCode.CONTINUATION:
                switch(partial)
                {
                    case OpCode.TEXT:
                        onPartialText(channel,frame,callback);
                        break;
                        
                    case OpCode.BINARY:
                        onPartialBinary(channel,frame,callback);
                        
                    default:
                        callback.failed(new IllegalStateException());
                        
                }
                break;
        }
    }

    @Override
    public void onClose(WebSocketChannel channel, CloseStatus close)
    {
    }

    @Override
    public void onError(WebSocketChannel channel, Throwable cause)
    {
    }

    public void onPing(WebSocketChannel channel, Frame frame, Callback callback)
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
    

    public void onPong(WebSocketChannel channel, Frame frame, Callback callback)
    {
        callback.succeeded();
    }

    public void onText(WebSocketChannel channel, String payload, Callback callback)
    {
        callback.succeeded();
    }

    public void onBinary(WebSocketChannel channel, ByteBuffer payload, Callback callback)
    {
        callback.succeeded();
    }

    public void onPartialText(WebSocketChannel channel, Frame frame, Callback callback)
    {
        if (utf8Buffer==null)
            utf8Buffer = new Utf8StringBuffer(frame.getPayloadLength()*2);
        
        // TODO handle encoding errors 
        // TODO enforce a max size from policy
        
        utf8Buffer.append(frame.getPayload());

        if (frame.isFin())
        {
            partial = -1;
            String text = utf8Buffer.toString();
            utf8Buffer.reset();
            onText(channel,text,callback);
        }
        else
        {
            partial = OpCode.TEXT;
            callback.succeeded();
        }
    }

    public void onPartialBinary(WebSocketChannel channel, Frame frame, Callback callback)
    {
        // TODO use the pool?
        if (byteBuffer==null)
            byteBuffer = BufferUtil.allocate(frame.getPayloadLength()*2);
              
        // TODO enforce a max size from policy
        BufferUtil.append(byteBuffer,frame.getPayload());

        if (frame.isFin())
        {
            partial = -1;
            ByteBuffer payload = byteBuffer;
            byteBuffer = null;
            onBinary(channel,payload,callback);
        }
        else
        {
            partial = OpCode.BINARY;
            callback.succeeded();
        }
    }
    

}
