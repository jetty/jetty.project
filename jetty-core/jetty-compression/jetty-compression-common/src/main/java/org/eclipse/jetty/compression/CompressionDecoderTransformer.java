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

package org.eclipse.jetty.compression;

import java.io.IOException;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.content.ContentSourceTransformer;

public class CompressionDecoderTransformer extends ContentSourceTransformer
{
    private final Compression.Decoder _decoder;
    private Content.Chunk _chunk;

    public CompressionDecoderTransformer(Content.Source source, Compression.Decoder decoder)
    {
        super(source);
        _decoder = decoder;
    }

    @Override
    protected Content.Chunk transform(Content.Chunk inputChunk)
    {
        boolean retain = _chunk == null;
        if (_chunk == null)
            _chunk = inputChunk;
        if (_chunk == null)
            return null;
        if (Content.Chunk.isFailure(_chunk))
        {
            Content.Chunk failure = _chunk;
            _chunk = Content.Chunk.next(failure);
            return failure;
        }
        if (_chunk.isLast() && !_chunk.hasRemaining())
            return Content.Chunk.EOF;

        // Retain the input chunk because its ByteBuffer might be referenced by the Decoder.
        if (retain)
            _chunk.retain();
        try
        {
            RetainableByteBuffer decodedBuffer = _decoder.decode(_chunk.getByteBuffer());

            if (decodedBuffer != null && decodedBuffer.hasRemaining())
            {
                // The decoded ByteBuffer is a transformed "copy" of the
                // compressed one, so it has its own reference counter.
                return Content.Chunk.from(decodedBuffer.getByteBuffer(), _chunk.isLast() && !_chunk.hasRemaining(), decodedBuffer::release);
            }
            else
            {
                if (decodedBuffer != null)
                    decodedBuffer.release();
                // Could not decode more from this chunk, release it.
                Content.Chunk result = _chunk.isLast() ? Content.Chunk.EOF : null;
                _chunk.release();
                _chunk = null;
                return result;
            }
        }
        catch (IOException e)
        {
            return Content.Chunk.from(e);
        }
    }
}
