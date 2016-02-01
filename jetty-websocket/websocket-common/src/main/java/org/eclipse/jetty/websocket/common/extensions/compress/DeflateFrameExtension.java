//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.util.zip.DataFormatException;

import org.eclipse.jetty.websocket.api.BadPayloadException;
import org.eclipse.jetty.websocket.api.extensions.Frame;

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

        if ( frame.getType().isControl() || !frame.isRsv1() || !frame.hasPayload() )
        {
            nextIncomingFrame(frame);
            return;
        }

        try
        {
            ByteAccumulator accumulator = newByteAccumulator();
            decompress(accumulator, frame.getPayload());
            decompress(accumulator, TAIL_BYTES_BUF.slice());
            forwardIncoming(frame, accumulator);
        }
        catch (DataFormatException e)
        {
            throw new BadPayloadException(e);
        }
    }
}
