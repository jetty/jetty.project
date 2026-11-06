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

package org.eclipse.jetty.websocket.core.internal;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;

/// A [Frame] produced by the [Parser].
///
/// The payload is demasked on construction, and for `CLOSE` frames
/// the [CloseStatus] is parsed and validated from the payload.
public class ParsedFrame extends Frame implements CloseStatus.Supplier
{
    private final CloseStatus closeStatus;

    /// @param firstByte the first byte of the frame, with the FIN, RSV and OpCode bits
    /// @param mask the masking key, or `null` if the frame is not masked
    /// @param payload the payload of the frame
    public ParsedFrame(byte firstByte, byte[] mask, RetainableByteBuffer payload)
    {
        demask(mask, payload);
        super(firstByte, mask, payload);
        try
        {
            closeStatus = getOpCode() == OpCode.CLOSE
                ? hasPayload() ? CloseStatus.from(this) : CloseStatus.NO_CODE_STATUS
                : null;
        }
        catch (Throwable x)
        {
            // The call to super() retains, release
            // here if CloseStatus.from() throws.
            release();
            throw x;
        }
    }

    private static void demask(byte[] mask, RetainableByteBuffer payload)
    {
        if (mask == null)
            return;
        if (!payload.hasRemaining())
            return;
        int maskInt = ByteBuffer.wrap(mask).getInt();
        // The payload may be backed by multiple ByteBuffers, so the
        // mask offset must be carried across Reader invocations.
        int[] maskOffset = new int[1];
        payload.read(b ->
        {
            int offset = maskOffset[0];
            int position = b.position();
            // Demask byte by byte until aligned with the mask.
            while (b.hasRemaining() && (offset & 3) != 0)
            {
                b.put((byte)(b.get(b.position()) ^ mask[offset & 3]));
                ++offset;
            }
            // Demask int by int, the alignment with the mask does not change.
            while (b.remaining() >= 4)
            {
                b.putInt(b.getInt(b.position()) ^ maskInt);
            }
            // Demask the remaining bytes.
            while (b.hasRemaining())
            {
                b.put((byte)(b.get(b.position()) ^ mask[offset & 3]));
                ++offset;
            }
            // Save the current mask offset in case the
            // buffer is made of multiple ByteBuffers.
            maskOffset[0] = offset;
            // Restore the position, so the payload is not consumed.
            b.position(position);
            return b.remaining();
        });
        Arrays.fill(mask, (byte)0);
    }

    @Override
    public CloseStatus getCloseStatus()
    {
        return closeStatus;
    }

    @Override
    public String toString()
    {
        String string = super.toString();
        return closeStatus == null ? string : string + ":" + closeStatus;
    }
}
