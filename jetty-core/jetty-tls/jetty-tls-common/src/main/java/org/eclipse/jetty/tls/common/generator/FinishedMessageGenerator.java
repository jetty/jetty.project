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

package org.eclipse.jetty.tls.common.generator;

import org.eclipse.jetty.tls.FinishedMessage;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class FinishedMessageGenerator extends MessageGenerator
{
    public FinishedMessageGenerator(ByteBufferPool byteBufferPool, boolean useDirectBuffers)
    {
        super(byteBufferPool, useDirectBuffers);
    }

    @Override
    public void generate(BufferAccumulator accumulator, Message message) throws Exception
    {
        generate(accumulator, (FinishedMessage)message);
    }

    private void generate(BufferAccumulator accumulator, FinishedMessage message) throws Exception
    {
        byte[] verifyData = message.verifyData();
        int length = verifyData.length;
        if (length > 0xFFFFFF)
            throw new IllegalStateException("could not generate FinishedMessage, too long");

        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(4 + length, isUseDirectBuffers());
        accumulator.addRetained(buffer);

        int typeAndLength = (message.type().code() << 24) | length;
        buffer.putInt(typeAndLength);

        buffer.put(verifyData);

        notifyMessageGenerated(message);
    }
}
