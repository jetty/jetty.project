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
import org.eclipse.jetty.tls.ext.EarlyDataExtension;
import org.eclipse.jetty.tls.ext.Extension;

public class EarlyDataExtensionGenerator implements ExtensionGenerator
{
    @Override
    public int type()
    {
        return EarlyDataExtension.CODE;
    }

    @Override
    public int generate(RetainableByteBuffer.Mutable accumulator, Extension extension)
    {
        return generate(accumulator, (EarlyDataExtension)extension);
    }

    private int generate(RetainableByteBuffer.Mutable accumulator, EarlyDataExtension extension)
    {
        accumulator.putShort((short)extension.code());
        long maxData = extension.maxData();
        if (maxData >= 0)
        {
            accumulator.putShort((short)4);
            accumulator.putInt((int)maxData);
            return 2 + 2 + 4;
        }
        else
        {
            accumulator.putShort((short)0);
            return 2 + 2;
        }
    }
}
