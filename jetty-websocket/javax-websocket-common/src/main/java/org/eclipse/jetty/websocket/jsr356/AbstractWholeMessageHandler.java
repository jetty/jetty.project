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

package org.eclipse.jetty.websocket.jsr356;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;

public abstract class AbstractWholeMessageHandler extends AbstractFrameTypeHandler
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
        // handle below here
        if (frame.getOpCode() == OpCode.TEXT)
            textMessage.reset();

        if(frame.hasPayload())
            textMessage.append(frame.getPayload());

        if (frame.isFin())
            onWholeText(textMessage.toString(), callback);
        else
            callback.succeeded();
    }

    @Override
    public void onBinary(Frame frame, Callback callback)
    {
        // handle below here
        if (frame.getOpCode() == OpCode.BINARY)
        {
            binaryMessage = ByteBuffer.allocate(frame.getPayloadLength());
        }

        if (frame.hasPayload())
        {
            if (BufferUtil.space(binaryMessage) < frame.getPayloadLength())
                binaryMessage = BufferUtil.ensureCapacity(binaryMessage,binaryMessage.capacity()+Math.max(binaryMessage.capacity(), frame.getPayloadLength()*3));

            BufferUtil.append(binaryMessage,frame.getPayload());
        }

        if (frame.isFin())
            onWholeBinary(binaryMessage, callback);
        else
            callback.succeeded();
    }
}
