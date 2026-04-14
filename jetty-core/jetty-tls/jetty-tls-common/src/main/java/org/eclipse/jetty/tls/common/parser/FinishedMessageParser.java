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

package org.eclipse.jetty.tls.common.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.FinishedMessage;
import org.eclipse.jetty.tls.Message;

public class FinishedMessageParser implements MessageParser
{
    private int cursor;
    private byte[] verifyData;

    @Override
    public Message parse(int messageLength, RetainableByteBuffer buffer)
    {
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        if (verifyData == null)
            verifyData = new byte[messageLength];
        int length = Math.min(messageLength - cursor, byteBuffer.remaining());
        byteBuffer.get(verifyData, cursor, length);
        cursor += length;
        if (cursor == messageLength)
        {
            FinishedMessage message = new FinishedMessage(verifyData);
            cursor = 0;
            verifyData = null;
            return message;
        }
        return null;
    }
}
