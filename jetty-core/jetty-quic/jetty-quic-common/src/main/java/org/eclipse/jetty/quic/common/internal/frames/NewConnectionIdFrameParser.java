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
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.NewConnectionIdFrame;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.quic.util.QuicException;
import org.eclipse.jetty.quic.util.VarLenInt;

public class NewConnectionIdFrameParser implements FrameParser
{
    @Override
    public Frame parse(RetainableByteBuffer buffer)
    {
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        long type = VarLenInt.decodeLong(byteBuffer);
        long sequenceNumber = VarLenInt.decodeLong(byteBuffer);
        long retirePriorTo = VarLenInt.decodeLong(byteBuffer);
        int length = byteBuffer.get() & 0xFF;
        if (length < 1 || length > 20)
            throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_connection_id", type);
        byte[] connectionId = new byte[length];
        byteBuffer.get(connectionId);
        byte[] statelessResetToken = new byte[16];
        byteBuffer.get(statelessResetToken);
        return new NewConnectionIdFrame(sequenceNumber, retirePriorTo, connectionId, statelessResetToken);
    }
}
