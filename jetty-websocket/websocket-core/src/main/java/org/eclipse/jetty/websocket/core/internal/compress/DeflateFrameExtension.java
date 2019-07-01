//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.internal.compress;

import java.util.zip.DataFormatException;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.BadPayloadException;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;

/**
 * Implementation of the
 * <a href="https://tools.ietf.org/id/draft-tyoshino-hybi-websocket-perframe-deflate.txt">deflate-frame</a>
 * extension seen out in the wild.
 */
public class DeflateFrameExtension extends CompressExtension
{
    private static final Logger LOG = Log.getLogger(DeflateFrameExtension.class);

    @Override
    public String getName()
    {
        return "deflate-frame";
    }

    @Override
    int getRsvUseMode()
    {
        return RSV_USE_ALWAYS;
    }

    @Override
    int getTailDropMode()
    {
        return TAIL_DROP_ALWAYS;
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        // Incoming frames are always non concurrent because
        // they are read and parsed with a single thread, and
        // therefore there is no need for synchronization.
        if ((OpCode.isControlFrame(frame.getOpCode())) || !frame.isRsv1() || !frame.hasPayload())
        {
            nextIncomingFrame(frame, callback);
            return;
        }

        try
        {
            //TODO fix this to use long instead of int
            if (getWebSocketCoreSession().getMaxFrameSize() > Integer.MAX_VALUE)
                throw new IllegalArgumentException("maxFrameSize too large for ByteAccumulator");
            ByteAccumulator accumulator = new ByteAccumulator((int)getWebSocketCoreSession().getMaxFrameSize());
            decompress(accumulator, frame.getPayload());
            decompress(accumulator, TAIL_BYTES_BUF.slice());
            forwardIncoming(frame, callback, accumulator);
        }
        catch (DataFormatException e)
        {
            throw new BadPayloadException(e);
        }
    }

    @Override
    public void setWebSocketCoreSession(WebSocketCoreSession coreSession)
    {
        // Frame auto-fragmentation must not be used with DeflateFrameExtension
        coreSession.setAutoFragment(false);
        super.setWebSocketCoreSession(coreSession);
    }
}
