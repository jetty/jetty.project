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

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.KeyShare;
import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.tls.ext.KeyShareExtension;

public class KeyShareExtensionGenerator implements ExtensionGenerator
{
    private final boolean client;

    public KeyShareExtensionGenerator(boolean client)
    {
        this.client = client;
    }

    @Override
    public int type()
    {
        return KeyShareExtension.CODE;
    }

    @Override
    public int generate(RetainableByteBuffer.Mutable accumulator, Extension extension)
    {
        return generate(accumulator, (KeyShareExtension)extension);
    }

    private int generate(RetainableByteBuffer.Mutable accumulator, KeyShareExtension extension)
    {
        accumulator.putShort((short)extension.code());
        List<KeyShare> keyShares = extension.keyShares();
        if (client)
        {
            int listLength = keyShares.stream()
                .mapToInt(keyShare -> 2 + 2 + keyShare.keyExchange().length)
                .sum();
            int totalLength = 2 + listLength;
            accumulator.putShort((short)totalLength);
            accumulator.putShort((short)listLength);
            for (KeyShare keyShare : keyShares)
            {
                accumulator.putShort((short)keyShare.namedGroup().code());
                byte[] keyExchange = keyShare.keyExchange();
                accumulator.putShort((short)keyExchange.length);
                accumulator.put(keyExchange);
            }
            return 2 + 2 + totalLength;
        }
        else
        {
            // There must be only one KeyShare.
            if (keyShares.size() != 1)
                throw new IllegalStateException("invalid key shares " + keyShares);
            KeyShare keyShare = keyShares.getFirst();
            // Detect whether it is a ServerHello or a RetryHelloRequest.
            int length = keyShare.keyExchange().length;
            if (length > 0)
            {
                // ServerHello.
                int totalLength = 2 + 2 + length;
                accumulator.putShort((short)totalLength);
                accumulator.putShort((short)keyShare.namedGroup().code());
                accumulator.putShort((short)length);
                accumulator.put(keyShare.keyExchange());
                return 2 + 2 + totalLength;
            }
            else
            {
                // RetryHelloRequest.
                int totalLength = 2;
                accumulator.putShort((short)totalLength);
                accumulator.putShort((short)keyShare.namedGroup().code());
                return 2 + 2 + totalLength;
            }
        }
    }
}
