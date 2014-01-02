//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.mux;

import java.io.IOException;

import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.io.FramePipes;

/**
 * Helpful utility class to send arbitrary mux events into a physical connection's IncomingFrames.
 * 
 * @see MuxDecoder
 */
public class MuxEncoder
{
    public static MuxEncoder toIncoming(IncomingFrames incoming)
    {
        return new MuxEncoder(FramePipes.to(incoming));
    }

    public static MuxEncoder toOutgoing(OutgoingFrames outgoing)
    {
        return new MuxEncoder(outgoing);
    }

    private MuxGenerator generator;

    private MuxEncoder(OutgoingFrames outgoing)
    {
        this.generator = new MuxGenerator();
        this.generator.setOutgoing(outgoing);
    }

    public void frame(long channelId, WebSocketFrame frame) throws IOException
    {
        this.generator.generate(channelId,frame,null);
    }

    public void op(MuxControlBlock op) throws IOException
    {
        WriteCallback callback = null;
        this.generator.generate(callback,op);
    }
}
