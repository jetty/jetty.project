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

import java.io.IOException;
import java.util.concurrent.Future;

import javax.net.websocket.SendResult;
import javax.net.websocket.extensions.FrameHandler;

import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.extensions.AbstractJettyFrameHandler;
import org.eclipse.jetty.websocket.common.io.OutgoingFrames;

/**
 * Process frames destined for the network layer.
 * <p>
 * Outgoing frames are not mangled by this handler, as they have already been captured by the {@link Muxer} and encapsulated.
 */
public class MuxerOutgoingFrameHandler extends AbstractJettyFrameHandler implements OutgoingFrames
{
    public MuxerOutgoingFrameHandler(FrameHandler nextHandler, Muxer muxer)
    {
        super(nextHandler);
        muxer.setOutgoingFramesHandler(this);
    }

    @Override
    public void handleJettyFrame(WebSocketFrame frame)
    {
        // pass through. Muxer already encapsulated frame.
        nextJettyHandler(frame);
    }

    @Override
    public Future<SendResult> outgoingFrame(WebSocketFrame frame) throws IOException
    {
        // TODO Auto-generated method stub
        return null;
    }
}
