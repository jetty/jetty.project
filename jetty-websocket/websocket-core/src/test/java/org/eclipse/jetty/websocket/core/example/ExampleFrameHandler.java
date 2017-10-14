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

package org.eclipse.jetty.websocket.core.example;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.WebSocketChannel;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;

class ExampleFrameHandler implements FrameHandler
{
    @Override
    public void onOpen(WebSocketChannel channel)
    {        
        WebSocketUpgradeHandler.LOG.debug("onOpen {}", channel);
        channel.outgoingFrame(new TextFrame().setPayload("Opened!"),
        new Callback()
        {
            @Override
            public void succeeded()
            {
                WebSocketUpgradeHandler.LOG.debug("onOpen write!");
            }

            @Override
            public void failed(Throwable x)
            {
                WebSocketUpgradeHandler.LOG.warn(x);
            }
        },
        BatchMode.OFF);
    }

    @Override
    public void onFrame(WebSocketChannel channel, Frame frame, Callback callback)
    {
        WebSocketUpgradeHandler.LOG.debug("onFrame {} {}", frame,channel);
        if (frame.getOpCode() == OpCode.TEXT)
        {
            channel.outgoingFrame(new TextFrame().setPayload("ECHO: "+BufferUtil.toUTF8String(frame.getPayload())),
            callback,
            BatchMode.OFF);
        }
    }

    @Override
    public void onClose(WebSocketChannel channel, CloseStatus close)
    {
        WebSocketUpgradeHandler.LOG.debug("onClose");
        
    }

    @Override
    public void onError(WebSocketChannel channel, Throwable cause)
    {
        WebSocketUpgradeHandler.LOG.warn("onError",cause);
    }
}
