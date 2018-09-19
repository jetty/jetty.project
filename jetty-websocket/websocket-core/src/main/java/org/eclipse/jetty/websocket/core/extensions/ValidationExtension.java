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

package org.eclipse.jetty.websocket.core.extensions;

import java.util.List;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.CloseException;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.MessageTooLargeException;
import org.eclipse.jetty.websocket.core.Parser;
import org.eclipse.jetty.websocket.core.ProtocolException;
import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.core.frames.Frame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.io.BatchMode;

public class ValidationExtension extends AbstractExtension
{
    private static final Logger LOG = Log.getLogger(ValidationExtension.class);

    @Override
    public String getName()
    {
        return "@validation";
    }

    @Override
    public void onReceiveFrame(org.eclipse.jetty.websocket.core.frames.Frame frame, Callback callback)
    {
        System.out.println("INCOMING");
        assertValidIncoming(frame);
        nextIncomingFrame(frame, callback);
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, BatchMode batchMode)
    {
        System.out.println("OUTGOING");
        assertValidOutgoing(frame);
        nextOutgoingFrame(frame,callback,batchMode);
    }



    public void assertValidIncoming(Frame frame)
    {
        assertValid(frame);

        // Assert Behavior Required by RFC-6455 / Section 5.1
        if (getPolicy().getBehavior() == WebSocketBehavior.SERVER)
        {
            if (!frame.isMasked())
                throw new ProtocolException("Client MUST mask all frames (RFC-6455: Section 5.1)");
        }
        else if (getPolicy().getBehavior() == WebSocketBehavior.CLIENT)
        {
            if (frame.isMasked())
                throw new ProtocolException("Server MUST NOT mask any frames (RFC-6455: Section 5.1)");
        }
    }

    public void assertValidOutgoing(Frame frame) throws CloseException
    {
        if (!OpCode.isKnown(frame.getOpCode()))
            throw new ProtocolException("Unknown opcode: " + frame.getOpCode());

        assertValid(frame);

        int payloadLength = (frame.getPayload()==null) ? 0 : frame.getPayload().remaining(); // TODO is this accurately getting length of payload

        // Sane Payload Length
        if (payloadLength > getPolicy().getMaxAllowedFrameSize())
            throw new MessageTooLargeException("Cannot handle payload lengths larger than " + getPolicy().getMaxAllowedFrameSize());

        if (frame.isControlFrame())
        {
            if (!frame.isFin())
                throw new ProtocolException("Fragmented Control Frame [" + OpCode.name(frame.getOpCode()) + "]");

            if (payloadLength > Frame.MAX_CONTROL_PAYLOAD)
                throw new ProtocolException("Invalid control frame payload length, [" + payloadLength + "] cannot exceed [" + Frame.MAX_CONTROL_PAYLOAD + "]");
        }
    }

    private void assertValid(Frame frame)
    {

        // Control Frame Validation
        if (frame.isControlFrame())
        {
            if (frame.isRsv1())
                throw new ProtocolException("Cannot have RSV1==true on Control frames");

            if (frame.isRsv2())
                throw new ProtocolException("Cannot have RSV2==true on Control frames");

            if (frame.isRsv3())
                throw new ProtocolException("Cannot have RSV3==true on Control frames");


            /*
             * RFC 6455 Section 5.5.1
             * close frame payload is specially formatted which is checked in CloseStatus
             */
            if (frame.getOpCode() == OpCode.CLOSE)
            {
                if (!(frame instanceof Parser.ParsedFrame)) // already check in parser
                    new CloseStatus(frame.getPayload());
            }
        }

        /*
         * RFC 6455 Section 5.2
         *
         * MUST be 0 unless an extension is negotiated that defines meanings for non-zero values. If a nonzero value is received and none of the negotiated
         * extensions defines the meaning of such a nonzero value, the receiving endpoint MUST _Fail the WebSocket Connection_.
         */
        List<? extends Extension > exts = getWebSocketChannel().getExtensionStack().getExtensions();

        boolean isRsv1InUse = false;
        boolean isRsv2InUse = false;
        boolean isRsv3InUse = false;
        for (Extension ext : exts)
        {
            if (ext.isRsv1User())
                isRsv1InUse = true;
            if (ext.isRsv2User())
                isRsv2InUse = true;
            if (ext.isRsv3User())
                isRsv3InUse = true;
        }

        if (frame.isRsv1() && !isRsv1InUse)
            throw new ProtocolException("RSV1 not allowed to be set");

        if (frame.isRsv2() && !isRsv2InUse)
            throw new ProtocolException("RSV2 not allowed to be set");

        if (frame.isRsv3() && !isRsv3InUse)
            throw new ProtocolException("RSV3 not allowed to be set");
    }
}
