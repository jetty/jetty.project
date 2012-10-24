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

package org.eclipse.jetty.websocket.core.extensions.mux;

import java.io.IOException;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.io.IncomingFrames;
import org.eclipse.jetty.websocket.core.io.OutgoingFrames;
import org.eclipse.jetty.websocket.core.protocol.WebSocketFrame;

/**
 * Helpful utility class to send arbitrary mux events into a physical connection's IncomingFrames.
 * 
 * @see MuxReducer
 */
public class MuxInjector implements OutgoingFrames
{
    private IncomingFrames incoming;
    private MuxGenerator generator;

    public MuxInjector(IncomingFrames incoming)
    {
        this.incoming = incoming;
        this.generator = new MuxGenerator();
        this.generator.setOutgoing(this);
    }

    public void op(long channelId, WebSocketFrame frame) throws IOException
    {
        this.generator.generate(channelId,frame);
    }

    public void op(MuxControlBlock op) throws IOException
    {
        this.generator.generate(op);
    }

    @Override
    public <C> void output(C context, Callback<C> callback, WebSocketFrame frame) throws IOException
    {
        this.incoming.incoming(frame);
    }
}
