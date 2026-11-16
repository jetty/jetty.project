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

import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.tls.ext.ServerNameExtension;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class ServerNameExtensionGenerator extends ExtensionGenerator
{
    public ServerNameExtensionGenerator(ByteBufferPool byteBufferPool, boolean useDirectBuffers)
    {
        super(byteBufferPool, useDirectBuffers, ServerNameExtension.CODE);
    }

    @Override
    public int generate(BufferAccumulator accumulator, Extension extension)
    {
        return generate(accumulator, (ServerNameExtension)extension);
    }

    private int generate(BufferAccumulator accumulator, ServerNameExtension extension)
    {
        String serverName = extension.serverName();
        // RFC 6066, section 3: names are ASCII.
        byte[] bytes = serverName.getBytes(StandardCharsets.US_ASCII);
        // Only one element in the list.
        int listLength = 1 + 2 + bytes.length;
        // Type length + list length.
        int totalLength = 2 + listLength;

        int capacity = 2 + 2 + totalLength;
        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);

        buffer.putShort((short)extension.code());
        buffer.putShort((short)totalLength);
        buffer.putShort((short)listLength);
        // Type host_name is 0x00.
        buffer.put((byte)0x00);
        // Name length.
        buffer.putShort((short)bytes.length);
        buffer.put(bytes);

        return capacity;
    }
}
