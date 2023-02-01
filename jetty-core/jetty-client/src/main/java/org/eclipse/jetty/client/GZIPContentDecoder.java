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

package org.eclipse.jetty.client;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.RetainableByteBufferPool;

/**
 * {@link ContentDecoder} for the "gzip" encoding.
 */
public class GZIPContentDecoder extends org.eclipse.jetty.http.GZIPContentDecoder implements ContentDecoder
{
    public static final int DEFAULT_BUFFER_SIZE = 8192;

    public GZIPContentDecoder()
    {
        this(DEFAULT_BUFFER_SIZE);
    }

    public GZIPContentDecoder(int bufferSize)
    {
        this(null, bufferSize);
    }

    public GZIPContentDecoder(RetainableByteBufferPool retainableByteBufferPool, int bufferSize)
    {
        super(retainableByteBufferPool, bufferSize);
    }

    @Override
    protected boolean decodedChunk(RetainableByteBuffer chunk)
    {
        super.decodedChunk(chunk);
        return true;
    }

    /**
     * Specialized {@link ContentDecoder.Factory} for the "gzip" encoding.
     */
    public static class Factory extends ContentDecoder.Factory
    {
        private final RetainableByteBufferPool retainableByteBufferPool;
        private final int bufferSize;

        public Factory()
        {
            this(DEFAULT_BUFFER_SIZE);
        }

        public Factory(int bufferSize)
        {
            this(null, bufferSize);
        }

        public Factory(RetainableByteBufferPool retainableByteBufferPool)
        {
            this(retainableByteBufferPool, DEFAULT_BUFFER_SIZE);
        }

        public Factory(RetainableByteBufferPool retainableByteBufferPool, int bufferSize)
        {
            super("gzip");
            this.retainableByteBufferPool = retainableByteBufferPool;
            this.bufferSize = bufferSize;
        }

        @Override
        public ContentDecoder newContentDecoder()
        {
            return new GZIPContentDecoder(retainableByteBufferPool, bufferSize);
        }
    }
}
