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
import com.github.luben.zstd.ZstdException;
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
    private boolean inputFinished = false;

    public ZstandardDecoder(ZstandardCompression compression)
    {
        this.compression = compression;
        this.decompressCtx = new ZstdDecompressCtx();
    }

    @Override
    public RetainableByteBuffer decode(ByteBuffer input) throws IOException
    {
        assert input.isDirect(); // zstd-jni requires input be a direct buffer

        if (inputFinished && input.hasRemaining())
            throw new IllegalStateException("finishInput already called, cannot read input buffer");

        RetainableByteBuffer outputBuffer = compression.acquireByteBuffer();
        outputBuffer.getByteBuffer().clear();
        try
        {
            boolean fullyFlushed = decompressCtx.decompressDirectByteBufferStream(outputBuffer.getByteBuffer(), input);
            if (fullyFlushed)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("fullyFlushed = TRUE");
                finished = true;
            }
            outputBuffer.getByteBuffer().flip();
            return outputBuffer;
        }
        catch (ZstdException e)
        {
            // consume remaining input (it is bad)
            input.position(input.limit());
            // release output buffer (we will not return it)
            outputBuffer.release();
            throw new IOException("Decoder failure", e);
        }
    }

    @Override
    public void finishInput() throws IOException
    {
        inputFinished = true;
    }

    @Override
    public boolean isOutputComplete()
    {
        return finished;
    }

    @Override
    public void close()
    {
        decompressCtx.close();
    }
}
