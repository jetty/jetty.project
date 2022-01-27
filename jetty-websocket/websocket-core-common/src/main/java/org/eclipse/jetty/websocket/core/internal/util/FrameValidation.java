//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.internal.util;

import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.exception.CloseException;
import org.eclipse.jetty.websocket.core.exception.MessageTooLargeException;
import org.eclipse.jetty.websocket.core.exception.ProtocolException;
import org.eclipse.jetty.websocket.core.internal.Parser;

/**
 * Some static utility methods for validating a {@link Frame} based on the state of its {@link CoreSession}.
 */
public class FrameValidation
{
    public static void assertValidIncoming(Frame frame, CoreSession coreSession)
    {
        assertValidFrame(frame, coreSession);

        // Validate frame size.
        long maxFrameSize = coreSession.getMaxFrameSize();
        if (maxFrameSize > 0 && frame.getPayloadLength() > maxFrameSize)
            throw new MessageTooLargeException("Cannot handle payload lengths larger than " + maxFrameSize);

        // Assert Incoming Frame Behavior Required by RFC-6455 / Section 5.1
        Behavior behavior = coreSession.getBehavior();
        switch (behavior)
        {
            case SERVER:
                if (!frame.isMasked())
                    throw new ProtocolException("Client MUST mask all frames (RFC-6455: Section 5.1)");
                break;

            case CLIENT:
                if (frame.isMasked())
                    throw new ProtocolException("Server MUST NOT mask any frames (RFC-6455: Section 5.1)");
                break;

            default:
                throw new IllegalStateException(behavior.toString());
        }

        /*
         * RFC 6455 Section 5.5.1
         * close frame payload is specially formatted which is checked in CloseStatus
         */
        if (frame.getOpCode() == OpCode.CLOSE)
        {
            if (!(frame instanceof Parser.ParsedFrame)) // already check in parser
                CloseStatus.getCloseStatus(frame); // return ignored as get used to validate there is a closeStatus
        }
    }

    public static void assertValidOutgoing(Frame frame, CoreSession coreSession) throws CloseException
    {
        assertValidFrame(frame, coreSession);

        // Validate frame size (allowed to be over max frame size if autoFragment is true).
        boolean autoFragment = coreSession.isAutoFragment();
        long maxFrameSize = coreSession.getMaxFrameSize();
        if (!autoFragment && maxFrameSize > 0 && frame.getPayloadLength() > maxFrameSize)
            throw new MessageTooLargeException("Cannot handle payload lengths larger than " + maxFrameSize);

        /*
         * RFC 6455 Section 5.5.1
         * close frame payload is specially formatted which is checked in CloseStatus
         */
        if (frame.getOpCode() == OpCode.CLOSE)
        {
            if (!(frame instanceof Parser.ParsedFrame)) // already check in parser
            {
                CloseStatus closeStatus = CloseStatus.getCloseStatus(frame);
                if (!CloseStatus.isTransmittableStatusCode(closeStatus.getCode()) && (closeStatus.getCode() != CloseStatus.NO_CODE))
                {
                    throw new ProtocolException("Frame has non-transmittable status code");
                }
            }
        }
    }

    public static void assertValidFrame(Frame frame, CoreSession coreSession)
    {
        if (!OpCode.isKnown(frame.getOpCode()))
            throw new ProtocolException("Unknown opcode: " + frame.getOpCode());

        int payloadLength = frame.getPayloadLength();
        if (frame.isControlFrame())
        {
            if (!frame.isFin())
                throw new ProtocolException("Fragmented Control Frame [" + OpCode.name(frame.getOpCode()) + "]");

            if (payloadLength > Frame.MAX_CONTROL_PAYLOAD)
                throw new ProtocolException("Invalid control frame payload length, [" + payloadLength + "] cannot exceed [" + Frame.MAX_CONTROL_PAYLOAD + "]");

            if (frame.isRsv1())
                throw new ProtocolException("Cannot have RSV1==true on Control frames");
            if (frame.isRsv2())
                throw new ProtocolException("Cannot have RSV2==true on Control frames");
            if (frame.isRsv3())
                throw new ProtocolException("Cannot have RSV3==true on Control frames");
        }
        else
        {
            /*
             * RFC 6455 Section 5.2
             *
             * MUST be 0 unless an extension is negotiated that defines meanings for non-zero values. If a nonzero value is received and none of the negotiated
             * extensions defines the meaning of such a nonzero value, the receiving endpoint MUST _Fail the WebSocket Connection_.
             */
            if (frame.isRsv1() && !coreSession.isRsv1Used())
                throw new ProtocolException("RSV1 not allowed to be set");
            if (frame.isRsv2() && !coreSession.isRsv2Used())
                throw new ProtocolException("RSV2 not allowed to be set");
            if (frame.isRsv3() && !coreSession.isRsv3Used())
                throw new ProtocolException("RSV3 not allowed to be set");
        }
    }
}
