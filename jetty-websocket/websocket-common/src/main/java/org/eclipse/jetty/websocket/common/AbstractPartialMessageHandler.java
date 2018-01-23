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

package org.eclipse.jetty.websocket.common;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.frames.OpCode;

public abstract class AbstractPartialMessageHandler extends AbstractPartialFrameHandler
{
    private Utf8StringBuilder textMessage = new Utf8StringBuilder();

    public abstract void onPartialText(String partialMessage, boolean isFin, Callback callback);

    public abstract void onPartialBinary(ByteBuffer partialMessage, boolean isFin, Callback callback);

    @Override
    public void onText(Frame frame, Callback callback)
    {
        super.onText(frame, callback);
        // handle below here
        if (frame.getOpCode() == OpCode.TEXT)
            textMessage.reset();
        textMessage.append(frame.getPayload());
        onPartialText(textMessage.takePartialString(), frame.isFin(), callback);
    }

    @Override
    public void onBinary(Frame frame, Callback callback)
    {
        super.onBinary(frame, callback);
        // handle below here
        ByteBuffer payload = frame.getPayload();
        if (payload == null)
            payload = BufferUtil.EMPTY_BUFFER;
        onPartialBinary(payload, frame.isFin(), callback);
    }
}
