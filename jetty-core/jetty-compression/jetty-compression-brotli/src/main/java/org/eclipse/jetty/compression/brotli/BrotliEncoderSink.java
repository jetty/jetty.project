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
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;

import com.aayushatharva.brotli4j.encoder.BrotliEncoderChannel;
import com.aayushatharva.brotli4j.encoder.Encoder;
import org.eclipse.jetty.compression.EncoderSink;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BrotliEncoderSink extends EncoderSink
{
    private static final Logger LOG = LoggerFactory.getLogger(BrotliEncoderSink.class);
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);
    private final BrotliCompression compression;
    private final SinkChannel writeChannel;
    // TODO: change to com.aayushatharva.brotli4j.encoder.EncoderJNI.Wrapper once new release is available for
    // https://github.com/hyperxpro/Brotli4j/issues/144
    private final BrotliEncoderChannel encoder;

    public BrotliEncoderSink(BrotliCompression compression, Content.Sink sink)
    {
        super(sink);
        this.compression = compression;
        this.writeChannel = new SinkChannel();
        try
        {
            Encoder.Parameters params = compression.getEncoderParams();
            this.encoder = new BrotliEncoderChannel(writeChannel, params);
            /* Path of a write looks like:
            Jetty component
            BrotliEncoderSink.write
            Brotli4jChannel.write
            JettyDecodedChannel.write
            Content.Sink.write
             */
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("write({}, {}, {})", last, byteBuffer, callback);
        try
        {
            encoder.write(byteBuffer);
            if (last)
            {
                encoder.close();
                offerWrite(true, EMPTY_BUFFER, callback);
            }
            else
            {
                callback.succeeded();
            }
        }
        catch (IOException e)
        {
            callback.failed(e);
        }
    }

    private class SinkChannel implements WritableByteChannel
    {
        private boolean closed = false;
        private boolean last = false;

        @Override
        public boolean isOpen()
        {
            return !closed;
        }

        @Override
        public void close() throws IOException
        {
            last = true;
            closed = true;
        }

        @Override
        public int write(ByteBuffer src) throws IOException
        {
            if (!isOpen())
                throw new ClosedChannelException();

            if (LOG.isDebugEnabled())
                LOG.debug("captured.write({})", BufferUtil.toDetailString(src));

            int len = src.remaining();
            offerWrite(false, src, Callback.NOOP);
            return len;
        }
    }
}
