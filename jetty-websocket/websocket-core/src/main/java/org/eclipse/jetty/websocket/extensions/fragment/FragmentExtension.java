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
package org.eclipse.jetty.websocket.extensions.fragment;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.Extension;
import org.eclipse.jetty.websocket.protocol.ExtensionConfig;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

public class FragmentExtension extends Extension
{
    private int maxLength = -1;

    @Override
    public <C> void output(C context, Callback<C> callback, final WebSocketFrame frame) throws IOException
    {
        if (frame.isControlFrame())
        {
            // Cannot fragment Control Frames
            nextOutput(context,callback,frame);
            return;
        }

        int length = frame.getPayloadLength();

        byte opcode = frame.getOpCode(); // original opcode
        ByteBuffer payload = frame.getPayload().slice();
        int originalLimit = payload.limit();
        int currentPosition = payload.position();

        if (maxLength <= 0)
        {
            // output original frame
            nextOutput(context,callback,frame);
            return;
        }

        boolean continuation = false;

        // break apart payload based on maxLength rules
        while (length > maxLength)
        {
            WebSocketFrame frag = new WebSocketFrame(frame);
            frag.setOpCode(opcode);
            frag.setFin(false); // always false here
            frag.setContinuation(continuation);
            payload.position(currentPosition);
            payload.limit(Math.min(payload.position() + maxLength,originalLimit));
            frag.setPayload(payload);

            nextOutputNoCallback(frag);

            length -= maxLength;
            opcode = OpCode.CONTINUATION;
            continuation = true;
            currentPosition = payload.limit();
        }

        // write remaining
        WebSocketFrame frag = new WebSocketFrame(frame);
        frag.setOpCode(opcode);
        frag.setFin(frame.isFin()); // use original fin
        frag.setContinuation(continuation);
        payload.position(currentPosition);
        payload.limit(originalLimit);
        frag.setPayload(payload);

        nextOutput(context,callback,frag);
    }

    @Override
    public void setConfig(ExtensionConfig config)
    {
        super.setConfig(config);

        maxLength = config.getParameter("maxLength",maxLength);
    }
}
