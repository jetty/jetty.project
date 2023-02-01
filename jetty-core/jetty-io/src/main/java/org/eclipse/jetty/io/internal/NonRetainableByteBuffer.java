//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io.internal;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.RetainableByteBuffer;

public class NonRetainableByteBuffer implements RetainableByteBuffer
{
    private final ByteBuffer byteBuffer;

    public NonRetainableByteBuffer(ByteBuffer byteBuffer)
    {
        this.byteBuffer = byteBuffer;
    }

    @Override
    public boolean isRetained()
    {
        return false;
    }

    @Override
    public ByteBuffer getByteBuffer()
    {
        return byteBuffer;
    }
}
