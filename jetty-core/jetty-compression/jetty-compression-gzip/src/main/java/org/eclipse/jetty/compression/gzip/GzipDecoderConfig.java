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

import org.eclipse.jetty.compression.DecoderConfig;
import org.eclipse.jetty.util.IO;

public class GzipDecoderConfig implements DecoderConfig
{
    private static final int DEFAULT_BUFFER_SIZE = IO.DEFAULT_BUFFER_SIZE;
    // Minimum buffer size to avoid issues with JDK-8133170.
    private static final int MIN_BUFFER_SIZE = 32;
    private int bufferSize = DEFAULT_BUFFER_SIZE;

    @Override
    public int getBufferSize()
    {
        return bufferSize;
    }

    @Override
    public void setBufferSize(int size)
    {
        this.bufferSize = Math.max(MIN_BUFFER_SIZE, size);
    }
}
