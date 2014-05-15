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

package org.eclipse.jetty.websocket.common.extensions.compress;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.OpCode;

/**
 * Implementation of the
 * <a href="https://tools.ietf.org/id/draft-tyoshino-hybi-websocket-perframe-deflate.txt">deflate-frame</a>
 * extension seen out in the wild.
 */
public class DeflateFrameExtension extends CompressExtension
{
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
    public void incomingFrame(Frame frame)
    {
        // Incoming frames are always non concurrent because
        // they are read and parsed with a single thread, and
        // therefore there is no need for synchronization.

        if (OpCode.isControlFrame(frame.getOpCode()) || !frame.isRsv1() || !frame.hasPayload())
        {
            nextIncomingFrame(frame);
            return;
        }

        ByteBuffer payload = frame.getPayload();
        int remaining = payload.remaining();
        byte[] input = new byte[remaining + TAIL_BYTES.length];
        payload.get(input, 0, remaining);
        System.arraycopy(TAIL_BYTES, 0, input, remaining, TAIL_BYTES.length);

        forwardIncoming(frame, decompress(input));
    }
}
