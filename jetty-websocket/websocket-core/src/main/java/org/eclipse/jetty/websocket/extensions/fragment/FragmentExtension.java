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

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.Extension;
import org.eclipse.jetty.websocket.protocol.ExtensionConfig;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

public class FragmentExtension extends Extension
{
    private int maxLength = -1;
    private int minFragments = 1;

    @Override
    public void output(WebSocketFrame frame)
    {
        if (frame.getOpCode().isControlFrame())
        {
            // Cannot fragment Control Frames
            getNextOutgoingFrames().output(frame);
            return;
        }

        int fragments = 1;
        int length = frame.getPayloadLength();

        OpCode opcode = frame.getOpCode();
        ByteBuffer payload = frame.getPayload().slice();
        int originalLimit = payload.limit();

        // break apart payload based on maxLength rules
        if (maxLength > 0)
        {
            while (length > maxLength)
            {
                fragments++;

                WebSocketFrame frag = new WebSocketFrame(frame);
                frag.setOpCode(opcode);
                frag.setFin(false);
                payload.limit(Math.min(payload.limit() + maxLength,originalLimit));
                frag.setPayload(payload);

                nextOutput(frag);

                length -= maxLength;
                opcode = OpCode.CONTINUATION;
            }
        }

        // break apart payload based on minimum # of fragments
        if (fragments < minFragments)
        {
            int fragmentsLeft = (minFragments - fragments);
            int fragLength = length / fragmentsLeft; // equal sized fragments

            while (fragments < minFragments)
            {
                fragments++;

                WebSocketFrame frag = new WebSocketFrame(frame);
                frag.setOpCode(opcode);
                frag.setFin(false);
                frag.setPayload(payload);

                nextOutput(frag);
                length -= fragLength;
                opcode = OpCode.CONTINUATION;
            }
        }

        // output whatever is left
        WebSocketFrame frag = new WebSocketFrame(frame);
        frag.setOpCode(opcode);
        payload.limit(originalLimit);
        frag.setPayload(payload);

        nextOutput(frag);
    }

    @Override
    public void setConfig(ExtensionConfig config)
    {
        super.setConfig(config);

        maxLength = config.getParameter("maxLength",maxLength);
        minFragments = config.getParameter("minFragments",minFragments);
    }
}
