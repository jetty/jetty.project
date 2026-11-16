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

import org.eclipse.jetty.tls.ext.EarlyDataExtension;
import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class EarlyDataExtensionGenerator extends ExtensionGenerator
{
    public EarlyDataExtensionGenerator(ByteBufferPool byteBufferPool, boolean useDirectBuffers)
    {
        super(byteBufferPool, useDirectBuffers, EarlyDataExtension.CODE);
    }

    @Override
    public int generate(BufferAccumulator accumulator, Extension extension)
    {
        return generate(accumulator, (EarlyDataExtension)extension);
    }

    private int generate(BufferAccumulator accumulator, EarlyDataExtension extension)
    {
        long maxData = extension.maxData();
        int totalLength = maxData > 0 ? 4 : 0;

        int capacity = 2 + 2 + totalLength;
        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);

        buffer.putShort((short)extension.code());
        buffer.putShort((short)totalLength);
        if (maxData > 0)
            buffer.putInt((int)maxData);

        return capacity;
    }
}
