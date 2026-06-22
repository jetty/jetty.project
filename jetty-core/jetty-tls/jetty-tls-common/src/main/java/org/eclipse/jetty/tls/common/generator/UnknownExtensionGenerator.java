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
import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.tls.ext.UnknownExtension;

public class UnknownExtensionGenerator implements ExtensionGenerator
{
    private final int code;

    public UnknownExtensionGenerator(int code)
    {
        this.code = code;
    }

    @Override
    public int type()
    {
        return code;
    }

    @Override
    public int generate(RetainableByteBuffer.Mutable accumulator, Extension extension)
    {
        return generate(accumulator, (UnknownExtension)extension);
    }

    private int generate(RetainableByteBuffer.Mutable accumulator, UnknownExtension extension)
    {
        accumulator.putShort((short)extension.code());
        int length = extension.bytes().length;
        accumulator.putShort((short)length);
        accumulator.put(extension.bytes());
        return 2 + 2 + length;
    }
}
