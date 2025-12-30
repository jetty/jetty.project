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

import java.util.List;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.tls.message.Extension;
import org.eclipse.jetty.quic.tls.message.SupportedVersionsExtension;
import org.eclipse.jetty.quic.tls.message.TLSVersion;

public class SupportedVersionsExtensionGenerator implements ExtensionGenerator
{
    @Override
    public Extension.Type type()
    {
        return Extension.Type.SUPPORTED_VERSIONS;
    }

    @Override
    public int generate(RetainableByteBuffer.Mutable accumulator, Extension extension)
    {
        return generate(accumulator, (SupportedVersionsExtension)extension);
    }

    private int generate(RetainableByteBuffer.Mutable accumulator, SupportedVersionsExtension extension)
    {
        accumulator.putShort((short)extension.type().code());
        List<TLSVersion> versions = extension.versions();
        int listLength = 2 * versions.size();
        int totalLength = 1 + listLength;
        accumulator.putShort((short)totalLength);
        accumulator.put((byte)listLength);
        for (TLSVersion version : versions)
        {
            accumulator.putShort((short)version.code());
        }
        return 2 + totalLength;
    }
}
