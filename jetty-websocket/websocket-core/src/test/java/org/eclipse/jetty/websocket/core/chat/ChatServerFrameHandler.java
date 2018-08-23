//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.chat;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;

class ChatServerFrameHandler implements FrameHandler
{
    private static Logger LOG = Log.getLogger(ChatServerFrameHandler.class);

    private Channel channel;
    
    @Override
    public void onOpen(Channel channel) throws Exception
    {
        LOG.info("onOpen {}",channel);
        this.channel = channel;
    }

    @Override
    public void onFrame(Frame frame, Callback callback) throws Exception
    {
        LOG.info("onFrame {}",frame);
        // just echo it for now
        String message = channel.getRemoteAddress()+" -> "+BufferUtil.toString(frame.getPayload());
        TextFrame echo = new TextFrame();
        echo.setPayload(message);
        channel.sendFrame(echo,callback,BatchMode.AUTO);
    }

    @Override
    public void onClosed(CloseStatus closeStatus) throws Exception
    {
        LOG.info("onClosed {}",closeStatus);
        this.channel = null;
    }

    @Override
    public void onError(Throwable cause) throws Exception
    {
        LOG.warn("onError",cause);
        this.channel = null;
    }
        

}
