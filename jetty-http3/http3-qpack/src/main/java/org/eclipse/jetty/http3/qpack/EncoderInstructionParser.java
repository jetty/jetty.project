//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.qpack;

import java.nio.ByteBuffer;

/**
 * Receives instructions coming from the remote Decoder as a sequence of unframed instructions.
 */
public class EncoderInstructionParser
{
    public void parse(ByteBuffer buffer)
    {
        byte firstByte = buffer.slice().get();

        if ((firstByte & 0x80) != 0)
        {
            boolean referenceDynamicTable = (firstByte & 0x40) != 0;

            parseInsertNameWithReference(buffer);
        }
        else if ((firstByte & 0x40) != 0)
            parseInsertWithLiteralName(buffer);
        else if ((firstByte & 0x20) != 0)
            parseSetDynamicTableCapacity(buffer);
        else
            parseDuplicate(buffer);
    }

    private void parseInsertNameWithReference(ByteBuffer buffer)
    {
    }

    private void parseInsertWithLiteralName(ByteBuffer buffer)
    {
    }

    private void parseSetDynamicTableCapacity(ByteBuffer buffer)
    {
    }

    private void parseDuplicate(ByteBuffer buffer)
    {
    }
}
