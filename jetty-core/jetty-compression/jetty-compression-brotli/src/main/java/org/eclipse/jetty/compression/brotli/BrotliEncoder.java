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

import java.io.IOException;
import java.nio.ByteBuffer;

import com.aayushatharva.brotli4j.encoder.Encoder;
import com.aayushatharva.brotli4j.encoder.EncoderJNI;
import com.aayushatharva.brotli4j.encoder.PreparedDictionary;
import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BrotliEncoder implements Compression.Encoder
{
    private static final Logger LOG = LoggerFactory.getLogger(BrotliEncoder.class);

    private EncoderJNI.Wrapper encoder;
    private ByteBuffer inputBuffer;

    public BrotliEncoder(BrotliCompression brotliCompression, ByteBufferPool pool, int outputBufferSize)
    {
        try
        {
            Encoder.Parameters params = brotliCompression.getEncoderParams();
            this.encoder = new EncoderJNI.Wrapper(outputBufferSize, params.quality(), params.lgwin(), params.mode());
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
        this.inputBuffer = encoder.getInputBuffer();
    }

    public void attachDictionary(PreparedDictionary dictionary) throws IOException
    {
        if (!encoder.attachDictionary(dictionary.getData()))
        {
            throw new IOException("Unable to attach dictionary: " + dictionary);
        }
    }

    @Override
    public void begin()
    {
        encoder.push(EncoderJNI.Operation.PROCESS, 0);
    }

    @Override
    public void setInput(ByteBuffer content)
    {
    }

    @Override
    public void finish()
    {
    }

    @Override
    public boolean finished()
    {
        return false;
    }

    @Override
    public boolean needsInput()
    {
        return isOpen();
    }

    @Override
    public int encode(ByteBuffer outputBuffer) throws IOException
    {
        return encoder.write(outputBuffer);
    }

    @Override
    public int trailerSize()
    {
        return 0;
    }

    @Override
    public RetainableByteBuffer initialBuffer()
    {
        return null;
    }

    @Override
    public void addTrailer(ByteBuffer outputBuffer)
    {
    }

    @Override
    public void cleanup() throws IOException
    {
        encoder.close();
    }
}
