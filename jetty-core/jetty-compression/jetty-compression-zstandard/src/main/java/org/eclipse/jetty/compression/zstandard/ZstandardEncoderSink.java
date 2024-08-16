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

import java.nio.ByteBuffer;

import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdException;
import com.github.luben.zstd.ZstdFrameProgression;
import org.eclipse.jetty.compression.EncoderSink;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.Callback;

public class ZstandardEncoderSink extends EncoderSink
{
    /**
     * zstd-jni MUST have direct buffers.
     */
    private static final ByteBuffer EMPTY_DIRECT_BUFFER = ByteBuffer.allocateDirect(0);
    private final ZstandardCompression compression;
    private final ZstdCompressCtx compressCtx;

    public ZstandardEncoderSink(ZstandardCompression compression, Content.Sink sink)
    {
        super(sink);
        this.compression = compression;
        this.compressCtx = new ZstdCompressCtx();
        this.compressCtx.setLevel(compression.getCompressionLevel());
    }

    @Override
    public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
    {
        if (!byteBuffer.hasRemaining())
        {
            // skip if progress not yet started.
            // this allows for empty body contents to not cause errors.
            ZstdFrameProgression frameProgression = compressCtx.getFrameProgression();
            if (frameProgression.getConsumed() <= 0)
            {
                callback.succeeded();
                return;
            }
        }

        // Requirement of zstd-jni
        if (!byteBuffer.isDirect())
        {
            // TODO: how to tell this.sink that writes have failed?
            callback.failed(new IllegalArgumentException("ByteBuffer must be direct for zstd-jni"));
            return;
        }
        // TODO: perhaps, if not-direct, grab direct from pool and copy buffer in?

        RetainableByteBuffer outputBuf = null;
        boolean callbackHandled = false;

        try
        {
            // fully consume input buffer using zstd-jni CONTINUE directive
            while (byteBuffer.hasRemaining())
            {
                if (outputBuf == null)
                    outputBuf = compression.acquireByteBuffer();
                outputBuf.getByteBuffer().clear();
                boolean flushed = compressCtx.compressDirectByteBufferStream(outputBuf.getByteBuffer(), byteBuffer, EndDirective.CONTINUE);
                outputBuf.getByteBuffer().flip();
                if (outputBuf.getByteBuffer().hasRemaining())
                {
                    Callback writeCallback = Callback.from(outputBuf::release);
                    if (!last && flushed && !byteBuffer.hasRemaining())
                    {
                        callbackHandled = true;
                        writeCallback = Callback.from(callback, writeCallback);
                    }
                    offerWrite(false, outputBuf.getByteBuffer(), writeCallback);
                    outputBuf = null;
                }
            }

            // if last, use zstd-jni END directive once.
            if (last)
            {
                if (outputBuf == null)
                    outputBuf = compression.acquireByteBuffer();
                outputBuf.getByteBuffer().clear();
                this.compressCtx.compressDirectByteBufferStream(outputBuf.getByteBuffer(), EMPTY_DIRECT_BUFFER, EndDirective.END);
                outputBuf.getByteBuffer().flip();
                if (outputBuf.getByteBuffer().hasRemaining())
                {
                    offerWrite(false, outputBuf.getByteBuffer(), Callback.from(outputBuf::release));
                    outputBuf = null;
                }

                // use zstd-jni FLUSH directive to flush remaining compressed bytes out
                // of the internal zstd buffers.
                boolean flushing = true;
                while (flushing)
                {
                    if (outputBuf == null)
                        outputBuf = compression.acquireByteBuffer();
                    outputBuf.getByteBuffer().clear();
                    boolean actualLast = this.compressCtx.compressDirectByteBufferStream(outputBuf.getByteBuffer(), EMPTY_DIRECT_BUFFER, EndDirective.FLUSH);
                    outputBuf.getByteBuffer().flip();
                    if (actualLast || outputBuf.getByteBuffer().hasRemaining())
                    {
                        Callback writeCallback = Callback.from(outputBuf::release);
                        if (actualLast)
                        {
                            callbackHandled = true;
                            writeCallback = Callback.from(callback, writeCallback);
                        }
                        offerWrite(actualLast, outputBuf.getByteBuffer(), writeCallback);
                        outputBuf = null;
                    }
                    if (actualLast)
                    {
                        flushing = false;
                        if (outputBuf != null)
                            outputBuf.release();
                        outputBuf = null;
                    }
                }
            }

            if (outputBuf != null)
                outputBuf.release();
            if (!callbackHandled)
                callback.succeeded();
        }
        catch (ZstdException e)
        {
            callback.failed(e);
        }
    }
}
