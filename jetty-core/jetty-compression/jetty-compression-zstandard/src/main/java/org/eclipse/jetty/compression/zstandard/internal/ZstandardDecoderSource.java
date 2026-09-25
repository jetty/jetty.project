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

package org.eclipse.jetty.compression.zstandard.internal;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;

import com.github.luben.zstd.ZstdDecompressCtx;
import org.eclipse.jetty.compression.DecoderSource;
import org.eclipse.jetty.compression.zstandard.ZstandardCompression;
import org.eclipse.jetty.compression.zstandard.ZstandardDecoderConfig;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class ZstandardDecoderSource extends DecoderSource
{
    private final ZstandardCompression compression;
    private final ZstdDecompressCtx decompressCtx;
    private final int bufferSize;
    private final Cleaner.Cleanable cleanable;

    public ZstandardDecoderSource(Content.Source source, ZstandardCompression compression, ZstandardDecoderConfig config)
    {
        super(source);
        this.compression = compression;
        this.decompressCtx = new ZstdDecompressCtx();
        this.decompressCtx.setMagicless(config.isMagicless());
        this.bufferSize = config.getBufferSize();
        this.cleanable = compression.getCleaner().register(this, decompressCtx::close);
    }

    @Override
    protected Content.Chunk transform(Content.Chunk inputChunk)
    {
        if (!inputChunk.hasRemaining() && inputChunk.isLast())
            return inputChunk;

        try (RetainableByteBuffer inputBuffer = inputChunk.acquire())
        {
            try (RetainableByteBuffer.Mutable outputBuffer = compression.acquireBuffer(bufferSize))
            {
                boolean last = inputChunk.isLast();
                boolean[] fullyFlushed = new boolean[1];
                inputBuffer.quietRead(input ->
                {
                    int position = input.position();

                    // Zstandard requires direct buffers.
                    RetainableByteBuffer.Mutable directBuffer;
                    ByteBuffer inputBB;
                    if (input.isDirect())
                    {
                        directBuffer = null;
                        inputBB = input;
                    }
                    else
                    {
                        directBuffer = compression.acquireBuffer(input.remaining());
                        directBuffer.put(input);
                        ByteBuffer[] result = new ByteBuffer[1];
                        directBuffer.quietRead(b ->
                        {
                            result[0] = b;
                            return 0;
                        });
                        inputBB = result[0];
                    }

                    try (directBuffer)
                    {
                        int r = inputBB.remaining();
                        outputBuffer.write(output ->
                        {
                            int p = output.position();
                            fullyFlushed[0] = decompressCtx.decompressDirectByteBufferStream(output, inputBB);
                            return output.position() - p;
                        });
                        int consumed = r - inputBB.remaining();

                        if (!input.isDirect())
                        {
                            // The original heap input ByteBuffer was fully consumed by the copy to the
                            // direct ByteBuffer, restore the position to what was effectively consumed.
                            input.position(position + consumed);
                        }

                        return consumed;
                    }
                });
                if (!fullyFlushed[0])
                    last = false;
                return Content.Chunk.from(outputBuffer, last);
            }
        }
    }

    @Override
    public void release()
    {
        cleanable.clean();
    }
}
