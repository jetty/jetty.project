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

import org.eclipse.jetty.tls.ext.ClientPreSharedKeyExtension;
import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.tls.ext.PreSharedKeyIdentity;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class ClientPreSharedKeyExtensionGenerator extends ExtensionGenerator
{
    public ClientPreSharedKeyExtensionGenerator(ByteBufferPool byteBufferPool, boolean useDirectBuffers)
    {
        super(byteBufferPool, useDirectBuffers, ClientPreSharedKeyExtension.CODE);
    }

    @Override
    public int generate(BufferAccumulator accumulator, Extension extension)
    {
        return generate(accumulator, (ClientPreSharedKeyExtension)extension);
    }

    private int generate(BufferAccumulator accumulator, ClientPreSharedKeyExtension extension)
    {
        List<PreSharedKeyIdentity> identities = extension.identities();
        // Identities length + binders length.
        int totalLength = 2 + 2;
        int identitiesLength = 0;
        int bindersLength = 0;
        for (PreSharedKeyIdentity identity : identities)
        {
            int identityLength = 2 + identity.identity().length + 4;
            identitiesLength += identityLength;
            int binderLength = 1 + identity.binder().length;
            bindersLength += binderLength;
            totalLength += identityLength + binderLength;
        }
        if (totalLength > 0xFFFF)
            throw new IllegalStateException("invalid pre-shared key extension length " + totalLength);

        int capacity = 2 + 2 + totalLength;
        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);

        buffer.putShort((short)extension.code());
        buffer.putShort((short)totalLength);
        buffer.putShort((short)identitiesLength);
        for (PreSharedKeyIdentity identity : identities)
        {
            buffer.putShort((short)identity.identity().length);
            buffer.put(identity.identity());
            buffer.putInt(identity.obfuscatedTicketAge());
        }
        if (extension.hasBinders())
        {
            buffer.putShort((short)bindersLength);
            for (PreSharedKeyIdentity identity : identities)
            {
                byte[] binder = identity.binder();
                buffer.put((byte)binder.length);
                buffer.put(binder);
            }
        }

        return capacity;
    }
}
