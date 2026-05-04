//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.common.internal.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.quic.util.QuicException;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.util.Utf8StringBuilder;

public class ConnectionCloseFrameParser implements FrameParser
{
    private final VarLenInt varLenInt;
    private int reasonMaxLength = 128;
    private State state = State.FRAME_TYPE;
    private boolean appError;
    private long errorCode;
    private long causeFrameType;
    private long reasonLength;
    private final Utf8StringBuilder reasonBuilder = new Utf8StringBuilder();

    public ConnectionCloseFrameParser(VarLenInt varLenInt)
    {
        this.varLenInt = varLenInt;
    }

    public int getReasonMaxLength()
    {
        return reasonMaxLength;
    }

    public void setReasonMaxLength(int reasonMaxLength)
    {
        this.reasonMaxLength = reasonMaxLength;
    }

    @Override
    public ConnectionCloseFrame parse(RetainableByteBuffer buffer)
    {
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        while (byteBuffer.hasRemaining())
        {
            switch (state)
            {
                case FRAME_TYPE ->
                {
                    appError = (byteBuffer.get() & 0xFF) == 0x1D;
                    state = State.ERROR_CODE;
                }
                case ERROR_CODE ->
                {
                    if (varLenInt.tryDecode(byteBuffer, v -> errorCode = v))
                        state = appError ? State.REASON_LENGTH : State.CAUSE_FRAME_TYPE;
                }
                case CAUSE_FRAME_TYPE ->
                {
                    if (varLenInt.tryDecode(byteBuffer, v -> causeFrameType = v))
                        state = State.REASON_LENGTH;
                }
                case REASON_LENGTH ->
                {
                    if (varLenInt.tryDecode(byteBuffer, v -> reasonLength = v))
                    {
                        if (reasonLength > reasonMaxLength)
                            throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_reason_length", appError ? 0x1D : 0x1C);
                        if (reasonLength == 0)
                            return result();
                        state = State.REASON;
                    }
                }
                case REASON ->
                {
                    int position = byteBuffer.position();
                    int length = (int)Math.min(reasonLength, byteBuffer.remaining());
                    reasonBuilder.append(byteBuffer.slice(position, length));
                    byteBuffer.position(position + length);
                    reasonLength -= length;
                    if (reasonLength == 0)
                        return result();
                }
            }
        }
        return null;
    }

    private ConnectionCloseFrame result()
    {
        String reason = reasonBuilder.toCompleteString();
        ConnectionCloseFrame frame = appError
            ? new ConnectionCloseFrame(errorCode, reason)
            : new ConnectionCloseFrame(errorCode, reason, causeFrameType);
        state = State.FRAME_TYPE;
        appError = false;
        errorCode = 0;
        causeFrameType = 0;
        reasonLength = 0;
        reasonBuilder.reset();
        return frame;
    }

    private enum State
    {
        FRAME_TYPE, ERROR_CODE, CAUSE_FRAME_TYPE, REASON_LENGTH, REASON
    }
}
