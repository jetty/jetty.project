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

package org.eclipse.jetty.quic.common.internal.packets;

import org.eclipse.jetty.io.RetainableByteBuffer;

public record EncodedPacketNumber(int number, int length)
{
    public void putTo(RetainableByteBuffer.Mutable accumulator)
    {
        switch (length)
        {
            case 1 -> accumulator.put((byte)number);
            case 2 -> accumulator.putShort((short)number);
            case 3 ->
            {
                accumulator.put((byte)(number >>> 16));
                accumulator.put((byte)(number >>> 8));
                accumulator.put((byte)number);
            }
            case 4 -> accumulator.putInt(number);
        }
    }
}
