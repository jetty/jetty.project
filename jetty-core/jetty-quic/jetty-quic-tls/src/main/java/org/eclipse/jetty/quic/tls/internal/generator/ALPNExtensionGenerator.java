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

package org.eclipse.jetty.quic.tls.internal.generator;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.tls.message.ALPNExtension;
import org.eclipse.jetty.quic.tls.message.Extension;

public class ALPNExtensionGenerator implements ExtensionGenerator
{
    @Override
    public Extension.Type type()
    {
        return Extension.Type.ALPN;
    }

    @Override
    public int generate(RetainableByteBuffer.Mutable accumulator, Extension extension)
    {
        return generate(accumulator, (ALPNExtension)extension);
    }

    private int generate(RetainableByteBuffer.Mutable accumulator, ALPNExtension extension)
    {
        accumulator.putShort((short)extension.type().code());
        List<String> protocols = extension.protocols();
        // RFC 7301: protocols are UTF-8 strings.
        List<byte[]> bytes = protocols.stream().map(p -> p.getBytes(StandardCharsets.UTF_8)).toList();
        int listLength = bytes.size() + bytes.stream().mapToInt(b -> b.length).sum();
        int totalLength = 2 + listLength;
        accumulator.putShort((short)totalLength);
        accumulator.putShort((short)listLength);
        for (int i = 0; i < bytes.size(); ++i)
        {
            byte[] b = bytes.get(i);
            if (b.length > 255)
                throw new IllegalStateException("invalid protocol '%s'".formatted(protocols.get(i)));
            accumulator.put((byte)b.length);
            accumulator.put(b);
        }
        return 2 + totalLength;
    }
}
