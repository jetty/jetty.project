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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.tls.message.Extension;

public class ExtensionsGenerator
{
    private final Map<Integer, ExtensionGenerator> generators = new HashMap<>();
    private final ByteBufferPool byteBufferPool;

    public ExtensionsGenerator(ByteBufferPool byteBufferPool)
    {
        this.byteBufferPool = byteBufferPool;
        put(new ServerNameExtensionGenerator());
        put(new ALPNExtensionGenerator());
        put(new KeyShareExtensionGenerator());
        put(new SupportedGroupsExtensionGenerator());
        put(new SupportedVersionsExtensionGenerator());
        put(new QuicTransportParametersExtensionGenerator());
    }

    public ExtensionGenerator put(ExtensionGenerator generator)
    {
        return generators.put(generator.getType(), generator);
    }

    public void generate(RetainableByteBuffer.Mutable accumulator, List<Extension> extensions)
    {
        int totalLength = 0;
        RetainableByteBuffer.Mutable extensionsAccumulator = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, true, -1, 0, 0);
        for (Extension extension : extensions)
        {
            ExtensionGenerator generator = generators.get(extension.type());
            if (generator != null)
                totalLength += generator.generate(extensionsAccumulator, extension);
            else
                throw new UnsupportedOperationException("could not generate unsupported TLS extension " + extensions);
        }
        accumulator.putShort((short)totalLength);
        accumulator.add(extensionsAccumulator);
    }
}
