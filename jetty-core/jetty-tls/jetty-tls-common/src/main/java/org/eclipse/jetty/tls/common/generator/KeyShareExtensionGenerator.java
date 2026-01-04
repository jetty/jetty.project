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

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.KeyShare;
import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.tls.ext.KeyShareExtension;

public class KeyShareExtensionGenerator implements ExtensionGenerator
{
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
        int listLength = extension.keyShares().stream()
            .mapToInt(keyShare -> 2 + 2 + keyShare.keyExchange().length)
            .sum();
        int totalLength = 2 + listLength;
        accumulator.putShort((short)totalLength);
        accumulator.putShort((short)listLength);
        for (KeyShare keyShare : extension.keyShares())
        {
            accumulator.putShort((short)keyShare.group().code());
            byte[] keyExchange = keyShare.keyExchange();
            accumulator.putShort((short)keyExchange.length);
            accumulator.put(keyExchange);
        }
        return 2 + 2 + totalLength;
    }
}
