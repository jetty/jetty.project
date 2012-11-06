//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.extensions;

import javax.net.websocket.extensions.FrameHandler;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.WebSocketFrame;

public abstract class AbstractJettyFrameHandler extends FrameHandler
{
    private Logger log;

    public AbstractJettyFrameHandler(FrameHandler nextHandler)
    {
        super(nextHandler);
        log = Log.getLogger(this.getClass());
    }

    /**
     * Incoming for javax.net.websocket based frames.
     * <p>
     * Note: As of 006EDR of the JavaWebSocket API is, the {@link javax.net.websocket.extensions.Frame} interface is insufficient to handle the intricacies of
     * Extensions. The implementations of {@link javax.net.websocket.extensions.Extension} within Jetty will attempt to cast to
     * {@link org.eclipse.jetty.websocket.common.WebSocketFrame} to handle only jetty managed/created WebSocketFrames.
     */
    @Override
    public final void handleFrame(javax.net.websocket.extensions.Frame f)
    {
        if (f instanceof WebSocketFrame)
        {
            handleJettyFrame((WebSocketFrame)f);
        }
        else
        {
            // [006EDR]
            throw new RuntimeException("Unsupported, Frame type [" + f.getClass().getName() + "] must be an instanceof [" + WebSocketFrame.class.getName()
                    + "]");
        }
    }

    public abstract void handleJettyFrame(WebSocketFrame frame);

    protected void nextJettyHandler(WebSocketFrame frame)
    {
        FrameHandler next = getNextHandler();
        if (next == null)
        {
            log.debug("No next handler (ending chain) {}",frame);
            return;
        }
        if (log.isDebugEnabled())
        {
            log.debug("nextHandler({}) -> {}",frame,next);
        }
        next.handleFrame(frame);
    }
}
