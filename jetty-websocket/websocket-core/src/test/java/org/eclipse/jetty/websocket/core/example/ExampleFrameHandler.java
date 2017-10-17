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

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.AbstractFrameHandler;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.WebSocketChannel;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;

class ExampleFrameHandler extends AbstractFrameHandler
{
    private static Logger LOG = Log.getLogger(ExampleFrameHandler.class);

    @Override
    public void onOpen(WebSocketChannel channel)
    {        
        LOG.info("onOpen {}", channel);
        super.onOpen(channel);
        channel.outgoingFrame(new TextFrame().setPayload("Opened!"),
        new Callback()
        {
            @Override
            public void succeeded()
            {
                LOG.debug("onOpen write!");
            }

            @Override
            public void failed(Throwable x)
            {
                LOG.warn(x);
            }
        },
        BatchMode.OFF);
        
    }

    @Override
    public void onText(WebSocketChannel channel, String payload, Callback callback)
    {
        LOG.info("onText {} {}", payload,channel);
        channel.outgoingFrame(new TextFrame().setPayload("ECHO: "+payload),
                callback,
                BatchMode.OFF);
    }
    
    @Override
    public void onClose(WebSocketChannel channel, CloseStatus close)
    {
        LOG.info("onClose");
        
    }

    @Override
    public void onError(WebSocketChannel channel, Throwable cause)
    {
        LOG.warn("onError",cause);
    }
}
