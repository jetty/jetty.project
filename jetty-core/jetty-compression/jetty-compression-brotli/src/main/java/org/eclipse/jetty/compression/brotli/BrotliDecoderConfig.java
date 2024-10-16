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

package org.eclipse.jetty.compression.brotli;

import org.eclipse.jetty.compression.DecoderConfig;

public class BrotliDecoderConfig implements DecoderConfig
{
    /**
     * Default Buffer Size as found in Brotli4j.
     */
    public static final int DEFAULT_BUFFER_SIZE = 16384;
    public static final int MIN_BUFFER_SIZE = 32;
    private int bufferSize = DEFAULT_BUFFER_SIZE;

    @Override
    public int getBufferSize()
    {
        return bufferSize;
    }

    @Override
    public void setBufferSize(int size)
    {
        this.bufferSize = this.bufferSize = Math.max(MIN_BUFFER_SIZE, size);
    }
}
