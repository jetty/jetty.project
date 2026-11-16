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

import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;

/// A generator for a TLS extension.
public abstract class ExtensionGenerator
{
    private final ByteBufferPool byteBufferPool;
    private final boolean useDirectBuffers;
    private final int code;

    protected ExtensionGenerator(ByteBufferPool byteBufferPool, boolean useDirectBuffers, int code)
    {
        this.byteBufferPool = byteBufferPool;
        this.useDirectBuffers = useDirectBuffers;
        this.code = code;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return byteBufferPool;
    }

    public boolean isUseDirectBuffers()
    {
        return useDirectBuffers;
    }

    /// @return the TLS extension code
    public int type()
    {
        return code;
    }

    /// @param accumulator the accumulator to generate the extension bytes into
    /// @param extension the extension to generate
    /// @return the number of bytes generated for the extension,
    /// including the extension code, length, and body
    public abstract int generate(BufferAccumulator accumulator, Extension extension);
}
