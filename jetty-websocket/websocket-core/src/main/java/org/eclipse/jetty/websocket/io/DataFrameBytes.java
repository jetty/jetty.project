// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.io;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

public class DataFrameBytes<C> extends FrameBytes<C>
{
    private static final Logger LOG = Log.getLogger(DataFrameBytes.class);
    private ByteBuffer buffer;

    public DataFrameBytes(AbstractWebSocketConnection connection, Callback<C> callback, C context, WebSocketFrame frame)
    {
        super(connection,callback,context,frame);
    }

    @Override
    public void completed(C context)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("completed({}) - frame.remaining() = {}",context,frame.remaining());
        }

        connection.getBufferPool().release(buffer);

        if (frame.remaining() > 0)
        {
            // We have written a partial frame per windowing size.
            // We need to keep the correct ordering of frames, to avoid that another
            // Data frame for the same stream is written before this one is finished.
            connection.getQueue().prepend(this);
        }
        else
        {
            super.completed(context);
        }
        connection.flush();
    }

    @Override
    public ByteBuffer getByteBuffer()
    {
        try
        {
            int windowSize = connection.getPolicy().getBufferSize();

            // TODO: windowSize should adjust according to some sort of flow control rules.
            buffer = connection.getGenerator().generate(windowSize,frame);
            return buffer;
        }
        catch (Throwable x)
        {
            failed(context,x);
            return null;
        }
    }
}