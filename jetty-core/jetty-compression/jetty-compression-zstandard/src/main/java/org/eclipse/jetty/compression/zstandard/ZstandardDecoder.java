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

package org.eclipse.jetty.compression.zstandard;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.github.luben.zstd.ZstdDecompressCtx;
import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Zstandard Decoder (decompress)
 */
public class ZstandardDecoder implements Compression.Decoder
{
    private static final Logger LOG = LoggerFactory.getLogger(ZstandardDecoder.class);

    private final ZstandardCompression compression;
    private final ZstdDecompressCtx decompressCtx;
    private boolean finished = false;

    public ZstandardDecoder(ZstandardCompression compression)
    {
        this.compression = compression;
        this.decompressCtx = new ZstdDecompressCtx();
    }

    @Override
    public RetainableByteBuffer decode(ByteBuffer input) throws IOException
    {
        assert input.isDirect(); // zstd-jni requires input be a direct buffer
        RetainableByteBuffer outputBuffer = compression.acquireByteBuffer();
        outputBuffer.getByteBuffer().clear();
        boolean fullyFlushed = decompressCtx.decompressDirectByteBufferStream(outputBuffer.getByteBuffer(), input);
        if (fullyFlushed)
        {
            LOG.debug("fullyFlushed = TRUE");
            finished = true;
        }
        outputBuffer.getByteBuffer().flip();
        return outputBuffer;
    }

    @Override
    public boolean isFinished()
    {
        return finished;
    }

    @Override
    public void close()
    {
        decompressCtx.close();
    }
}
