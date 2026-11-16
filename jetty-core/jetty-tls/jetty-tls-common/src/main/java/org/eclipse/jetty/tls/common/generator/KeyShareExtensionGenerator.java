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

import org.eclipse.jetty.tls.KeyShare;
import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.tls.ext.KeyShareExtension;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class KeyShareExtensionGenerator extends ExtensionGenerator
{
    private final boolean client;

    public KeyShareExtensionGenerator(ByteBufferPool byteBufferPool, boolean useDirectBuffers, boolean client)
    {
        super(byteBufferPool, useDirectBuffers, KeyShareExtension.CODE);
        this.client = client;
    }

    @Override
    public int generate(BufferAccumulator accumulator, Extension extension)
    {
        return generate(accumulator, (KeyShareExtension)extension);
    }

    private int generate(BufferAccumulator accumulator, KeyShareExtension extension)
    {
        List<KeyShare> keyShares = extension.keyShares();
        if (client)
        {
            int listLength = keyShares.stream()
                .mapToInt(keyShare -> 2 + 2 + keyShare.keyExchange().length)
                .sum();
            int totalLength = 2 + listLength;

            int capacity = 2 + 2 + totalLength;
            RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
            accumulator.addRetained(buffer);

            buffer.putShort((short)extension.code());
            buffer.putShort((short)totalLength);
            buffer.putShort((short)listLength);
            for (KeyShare keyShare : keyShares)
            {
                buffer.putShort((short)keyShare.namedGroup().code());
                byte[] keyExchange = keyShare.keyExchange();
                buffer.putShort((short)keyExchange.length);
                buffer.put(keyExchange);
            }

            return capacity;
        }
        else
        {
            // There must be only one KeyShare.
            if (keyShares.size() != 1)
                throw new IllegalStateException("invalid key shares " + keyShares);
            KeyShare keyShare = keyShares.getFirst();
            // Detect whether it is a ServerHello or a RetryHelloRequest.
            int length = keyShare.keyExchange().length;
            // ServerHello: group + length + key exchange.
            // RetryHelloRequest: group only.
            int totalLength = length > 0 ? 2 + 2 + length : 2;

            int capacity = 2 + 2 + totalLength;
            RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
            accumulator.addRetained(buffer);

            buffer.putShort((short)extension.code());
            buffer.putShort((short)totalLength);
            buffer.putShort((short)keyShare.namedGroup().code());
            if (length > 0)
            {
                buffer.putShort((short)length);
                buffer.put(keyShare.keyExchange());
            }

            return capacity;
        }
    }
}
