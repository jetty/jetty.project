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

package org.eclipse.jetty.websocket.io;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

public class ControlFrameBytes<C> extends FrameBytes<C>
{
    private static final Logger LOG = Log.getLogger(ControlFrameBytes.class);
    private ByteBuffer buffer;

    public ControlFrameBytes(AbstractWebSocketConnection connection, Callback<C> callback, C context, WebSocketFrame frame)
    {
        super(connection,callback,context,frame);
    }

    @Override
    public void completed(C context) {
        LOG.debug("completed() - frame: {}",frame);
        connection.getBufferPool().release(buffer);

        super.completed(context);

        if (frame.getOpCode() == OpCode.CLOSE)
        {
            // Disconnect the connection (no more packets/frames)
            connection.disconnect(false);
        }
        else
        {
            connection.flush();
        }
    }

    @Override
    public ByteBuffer getByteBuffer()
    {
        if (buffer == null)
        {
            buffer = connection.getGenerator().generate(frame);
        }
        return buffer;
    }
}