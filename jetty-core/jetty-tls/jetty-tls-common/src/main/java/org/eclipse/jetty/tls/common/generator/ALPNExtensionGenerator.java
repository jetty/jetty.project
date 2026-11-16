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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.tls.ext.ALPNExtension;
import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class ALPNExtensionGenerator extends ExtensionGenerator
{
    public ALPNExtensionGenerator(ByteBufferPool byteBufferPool, boolean useDirectBuffers)
    {
        super(byteBufferPool, useDirectBuffers, ALPNExtension.CODE);
    }

    @Override
    public int generate(BufferAccumulator accumulator, Extension extension)
    {
        return generate(accumulator, (ALPNExtension)extension);
    }

    private int generate(BufferAccumulator accumulator, ALPNExtension extension)
    {
        List<String> protocols = extension.protocols();
        List<byte[]> bytes = new ArrayList<>(protocols.size());
        int listLength = 0;
        for (String protocol : protocols)
        {
            // RFC 7301: protocols are UTF-8 strings.
            byte[] b = protocol.getBytes(StandardCharsets.UTF_8);
            if (b.length > 255)
                throw new IllegalStateException("invalid protocol '%s'".formatted(protocol));
            bytes.add(b);
            listLength += 1 + b.length;
        }
        int totalLength = 2 + listLength;

        int capacity = 2 + 2 + totalLength;
        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);

        buffer.putShort((short)extension.code());
        buffer.putShort((short)totalLength);
        buffer.putShort((short)listLength);
        for (byte[] b : bytes)
        {
            buffer.put((byte)b.length);
            buffer.put(b);
        }

        return capacity;
    }
}
