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

package org.eclipse.jetty.compression.gzip;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.Callback;

class ReleaseBuffer implements Callback
{
    private final RetainableByteBuffer buffer;

    public ReleaseBuffer(RetainableByteBuffer buffer)
    {
        this.buffer = buffer;
    }

    @Override
    public void succeeded()
    {
        buffer.release();
    }

    @Override
    public void failed(Throwable x)
    {
        buffer.release();
    }
}
