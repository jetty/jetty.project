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
                onPing(frame,callback);
                break;
         
            case OpCode.PONG:
                onPong(frame,callback);
                break;
                
            case OpCode.TEXT:
                // TODO is this the right abstract assembly model? should there be one?
                if (frame.isFin())
                {
                    if (utf8Buffer==null)
                        utf8Buffer = new Utf8StringBuffer(Math.max(1024,frame.getPayloadLength()*2));

                    if (frame.hasPayload())
                         utf8Buffer.append(frame.getPayload());

                    String text = utf8Buffer.toString();
                    utf8Buffer.reset();
                    onText(text,callback);
                }
                else
                    onPartialText(frame,callback);
                break;
                
            case OpCode.BINARY:
                // TODO is this the right abstract assembly model? should there be one?
                if (frame.isFin())
                    onBinary(frame.getPayload(),callback);
                else
                    onPartialBinary(frame,callback);
                break;
                
                
            case OpCode.CONTINUATION:
                switch(partial)
                {
                    case OpCode.TEXT:
                        onPartialText(frame,callback);
                        break;
                        
                    case OpCode.BINARY:
                        onPartialBinary(frame,callback);
                        
                    default:
                        callback.failed(new IllegalStateException());
                        
                }
                break;
                
            case OpCode.CLOSE:
                onClose(frame,callback);
                break;
        }
    }


    @Override
    public void onError(Throwable cause)
    {
    }

    public void onPing(Frame frame, Callback callback)
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
    

    public void onPong(Frame frame, Callback callback)
    {
        callback.succeeded();
    }

    public void onText(String payload, Callback callback)
    {
        callback.succeeded();
    }

    public void onBinary(ByteBuffer payload, Callback callback)
    {
        callback.succeeded();
    }

    public void onPartialText(Frame frame, Callback callback)
    {
        if (utf8Buffer==null)
            utf8Buffer = new Utf8StringBuffer(Math.max(1024,frame.getPayloadLength()*2));

        if (frame.hasPayload())
             utf8Buffer.append(frame.getPayload());

        if (frame.isFin())
        {
            partial = -1;
            String text = utf8Buffer.toString();
            utf8Buffer.reset();
            onText(text,callback);
        }
        else
        {
            partial = OpCode.TEXT;
            callback.succeeded();
        }
    }

    public void onPartialBinary(Frame frame, Callback callback)
    {
        // TODO use the pool?
        if (byteBuffer==null)
            byteBuffer = BufferUtil.allocate(Math.max(1024,frame.getPayloadLength()*2));
              
        if (frame.hasPayload())
            BufferUtil.append(byteBuffer,frame.getPayload());

        if (frame.isFin())
        {
            partial = -1;
            ByteBuffer payload = byteBuffer;
            byteBuffer = null;
            onBinary(payload,callback);
        }
        else
        {
            partial = OpCode.BINARY;
            callback.succeeded();
        }
    }

    public void onClose(Frame frame, Callback callback)
    {
        callback.succeeded();
    }

    @Override
    public void onClosed(CloseStatus closeStatus)
    {
        
    }

}
