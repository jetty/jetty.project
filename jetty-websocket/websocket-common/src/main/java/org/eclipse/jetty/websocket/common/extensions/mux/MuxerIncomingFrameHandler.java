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

package org.eclipse.jetty.websocket.common.extensions.mux;

import javax.net.websocket.extensions.FrameHandler;

import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.extensions.AbstractJettyFrameHandler;
import org.eclipse.jetty.websocket.common.io.IncomingFrames;

/**
 * Process incoming frames and forward them off to the Muxer.
 */
public class MuxerIncomingFrameHandler extends AbstractJettyFrameHandler
{
    private final IncomingFrames muxerHandler;

    public MuxerIncomingFrameHandler(FrameHandler nextHandler, Muxer muxer)
    {
        super(nextHandler);
        this.muxerHandler = muxer;
    }

    @Override
    public void handleJettyFrame(WebSocketFrame frame)
    {
        this.muxerHandler.incomingFrame(frame);
    }
}
