//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.core.frames.OpCode;

public abstract class AbstractWholeMessageHandler extends AbstractPartialFrameHandler
{
    private ByteBuffer binaryMessage;
    private Utf8StringBuilder textMessage = new Utf8StringBuilder();

    public void onWholeText(String wholeMessage, Callback callback)
    {
        callback.succeeded();
    }

    public void onWholeBinary(ByteBuffer wholeMessage, Callback callback)
    {
        callback.succeeded();
    }

    @Override
    public void onText(Frame frame, Callback callback)
    {
        super.onText(frame, callback);
        // handle below here
        if (frame.getOpCode() == OpCode.TEXT)
            textMessage.reset();
        textMessage.append(frame.getPayload());
        if (frame.isFin())
            onWholeText(textMessage.toString(), callback);
        else
            callback.succeeded();
    }

    @Override
    public void onBinary(Frame frame, Callback callback)
    {
        super.onBinary(frame, callback);
        // handle below here
        if (frame.getOpCode() == OpCode.BINARY)
            binaryMessage = ByteBuffer.allocate(frame.getPayloadLength());
        if (frame.hasPayload())
        {
            BufferUtil.ensureCapacity(binaryMessage, binaryMessage.remaining() + frame.getPayloadLength());
            BufferUtil.put(frame.getPayload(), binaryMessage);
        }
        if (frame.isFin())
            onWholeBinary(binaryMessage, callback);
        else
            callback.succeeded();
    }
}
