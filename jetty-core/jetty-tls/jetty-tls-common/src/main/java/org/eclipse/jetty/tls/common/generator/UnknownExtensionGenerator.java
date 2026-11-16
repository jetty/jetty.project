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

import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.tls.ext.UnknownExtension;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class UnknownExtensionGenerator extends ExtensionGenerator
{
    public UnknownExtensionGenerator(ByteBufferPool byteBufferPool, boolean useDirectBuffers, int code)
    {
        super(byteBufferPool, useDirectBuffers, code);
    }

    @Override
    public int generate(BufferAccumulator accumulator, Extension extension)
    {
        return generate(accumulator, (UnknownExtension)extension);
    }

    private int generate(BufferAccumulator accumulator, UnknownExtension extension)
    {
        byte[] bytes = extension.bytes();
        int totalLength = bytes.length;

        int capacity = 2 + 2 + totalLength;
        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);

        buffer.putShort((short)extension.code());
        buffer.putShort((short)totalLength);
        buffer.put(bytes);

        return capacity;
    }
}
