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

import org.eclipse.jetty.tls.NamedGroup;
import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.tls.ext.SupportedGroupsExtension;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class SupportedGroupsExtensionGenerator extends ExtensionGenerator
{
    public SupportedGroupsExtensionGenerator(ByteBufferPool byteBufferPool, boolean useDirectBuffers)
    {
        super(byteBufferPool, useDirectBuffers, SupportedGroupsExtension.CODE);
    }

    @Override
    public int generate(BufferAccumulator accumulator, Extension extension)
    {
        return generate(accumulator, (SupportedGroupsExtension)extension);
    }

    private int generate(BufferAccumulator accumulator, SupportedGroupsExtension extension)
    {
        List<NamedGroup> groups = extension.namedGroups();
        int listLength = 2 * groups.size();
        int totalLength = 2 + listLength;

        int capacity = 2 + 2 + totalLength;
        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);

        buffer.putShort((short)extension.code());
        buffer.putShort((short)totalLength);
        buffer.putShort((short)listLength);
        for (NamedGroup group : groups)
        {
            buffer.putShort((short)group.code());
        }

        return capacity;
    }
}
