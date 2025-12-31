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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.ext.Extension;

/// A generator for many TLS extensions.
public class ExtensionsGenerator
{
    private final Map<Integer, ExtensionGenerator> generators = new HashMap<>();

    public ExtensionsGenerator()
    {
        put(new ALPNExtensionGenerator());
        put(new KeyShareExtensionGenerator());
        put(new ServerNameExtensionGenerator());
        put(new SignatureAlgorithmsExtensionGenerator());
        put(new SupportedGroupsExtensionGenerator());
        put(new SupportedVersionsExtensionGenerator());
    }

    public ExtensionGenerator put(ExtensionGenerator generator)
    {
        return generators.put(generator.type(), generator);
    }

    /// @param accumulator the accumulator to generate the extensions bytes into
    /// @param extensions the extensions to generate
    /// @return the number of bytes generated for all the extensions.
    public int generate(RetainableByteBuffer.Mutable accumulator, List<Extension> extensions)
    {
        int totalLength = 0;
        for (Extension extension : extensions)
        {
            ExtensionGenerator generator = generators.get(extension.code());
            if (generator != null)
                totalLength += generator.generate(accumulator, extension);
            else
                throw new UnsupportedOperationException("could not generate unsupported TLS extension " + extension);
        }
        return totalLength;
    }
}
