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

import java.util.List;

import org.eclipse.jetty.tls.TLSVersion;
import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.tls.ext.SupportedVersionsExtension;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class SupportedVersionsExtensionGenerator extends ExtensionGenerator
{
    private final boolean client;

    public SupportedVersionsExtensionGenerator(ByteBufferPool byteBufferPool, boolean useDirectBuffers, boolean client)
    {
        super(byteBufferPool, useDirectBuffers, SupportedVersionsExtension.CODE);
        this.client = client;
    }

    @Override
    public int generate(BufferAccumulator accumulator, Extension extension)
    {
        return generate(accumulator, (SupportedVersionsExtension)extension);
    }

    private int generate(BufferAccumulator accumulator, SupportedVersionsExtension extension)
    {
        List<TLSVersion> versions = extension.versions();
        if (!client && versions.size() != 1)
            throw new IllegalStateException("server must send one TLS version " + versions);
        int listLength = 2 * versions.size();
        int totalLength = client ? 1 + listLength : listLength;

        int capacity = 2 + 2 + totalLength;
        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);

        buffer.putShort((short)extension.code());
        buffer.putShort((short)totalLength);
        if (client)
            buffer.put((byte)listLength);
        for (TLSVersion version : versions)
        {
            buffer.putShort((short)version.code());
        }

        return capacity;
    }
}
