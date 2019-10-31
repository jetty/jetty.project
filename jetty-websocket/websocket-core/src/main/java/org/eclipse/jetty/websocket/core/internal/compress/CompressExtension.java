//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.core.internal.compress;

import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.AbstractExtension;
import org.eclipse.jetty.websocket.core.BadPayloadException;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.MessageTooLargeException;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.ProtocolException;
import org.eclipse.jetty.websocket.core.internal.TransformingFlusher;

public abstract class CompressExtension extends AbstractExtension
{
    private static final byte[] TAIL_BYTES = new byte[]{0x00, 0x00, (byte)0xFF, (byte)0xFF};
    private static final ByteBuffer TAIL_BYTES_BUF = ByteBuffer.wrap(TAIL_BYTES);
    private static final Logger LOG = Log.getLogger(CompressExtension.class);

    protected enum CompressionMode
    {
        FRAME,
        MESSAGE
    }

    private static final int COMPRESS_BUFFER_SIZE = 8 * 1024;
    private static final int DECOMPRESS_BUF_SIZE = 8 * 1024;

    private final CompressionMode compressionMode;
    private final TransformingFlusher outgoingFlusher;
    private final TransformingFlusher incomingFlusher;
    private Deflater deflaterImpl;
    private Inflater inflaterImpl;
    private boolean incomingCompressed;

    protected CompressExtension()
    {
        compressionMode = getCompressionMode();

        outgoingFlusher = new TransformingFlusher()
        {
            @Override
            protected boolean transform(Frame frame, Callback callback, boolean batch, boolean first)
            {
                if (OpCode.isControlFrame(frame.getOpCode()))
                {
                    nextOutgoingFrame(frame, callback, batch);
                    return true;
                }

                return compress(frame, callback, batch, first);
            }
        };

        incomingFlusher = new TransformingFlusher()
        {
            @Override
            protected boolean transform(Frame frame, Callback callback, boolean batch, boolean first)
            {
                if (OpCode.isControlFrame(frame.getOpCode()))
                {
                    nextIncomingFrame(frame, callback);
                    return true;
                }

                if (first)
                {
                    switch (compressionMode)
                    {
                        case MESSAGE:
                            // This mode requires the RSV1 bit set only in the first frame.
                            // Subsequent continuation frames don't have RSV1 set, but are compressed.
                            switch (frame.getOpCode())
                            {
                                case OpCode.TEXT:
                                case OpCode.BINARY:
                                    incomingCompressed = frame.isRsv1();
                                    break;

                                case OpCode.CONTINUATION:
                                    if (frame.isRsv1())
                                        throw new ProtocolException("Invalid RSV1 set on permessage-deflate CONTINUATION frame");
                                    break;

                                default:
                                    break;
                            }
                            break;

                        case FRAME:
                            incomingCompressed = frame.isRsv1();
                            break;

                        default:
                            throw new IllegalStateException();
                    }

                    if (!incomingCompressed)
                    {
                        nextIncomingFrame(frame, callback);
                        return true;
                    }
                }

                if (frame.isFin())
                    incomingCompressed = false;

                try
                {
                    return decompress(frame, callback, first);
                }
                catch (DataFormatException e)
                {
                    throw new BadPayloadException(e);
                }
            }
        };
    }

    @Override
    public boolean isRsv1User()
    {
        return true;
    }

    abstract CompressionMode getCompressionMode();

    @Override
    public void sendFrame(Frame frame, Callback callback, boolean batch)
    {
        // Compressed frames may increase in size so we need the flusher to fragment them.
        outgoingFlusher.sendFrame(frame, callback, batch);
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        incomingFlusher.sendFrame(frame, callback, false);
    }

    protected boolean decompress(Frame frame, Callback callback, boolean first) throws DataFormatException
    {
        ByteBuffer data = frame.getPayload();
        long maxFrameSize = getWebSocketCoreSession().getMaxFrameSize();

        Inflater inflater = getInflater();
        if (first)
            inflater.setInput(data);

        // Decompress the payload.
        boolean finished = false;
        ByteAccumulator accumulator = new ByteAccumulator(getWebSocketCoreSession().getMaxFrameSize());
        while (true)
        {
            int bufferSize = DECOMPRESS_BUF_SIZE;
            if (maxFrameSize > 0)
                bufferSize = (int)Math.min(maxFrameSize - accumulator.size(), DECOMPRESS_BUF_SIZE);

            byte[] output = new byte[bufferSize];
            int read = inflater.inflate(output, 0, output.length);
            if (LOG.isDebugEnabled())
                LOG.debug("Decompress: read {} {}", read, toDetail(inflater));

            if (read <= 0)
            {
                // Do one more loop to finish.
                if (!finished && (frame.isFin() || compressionMode == CompressionMode.FRAME))
                {
                    inflater.setInput(TAIL_BYTES_BUF.slice());
                    finished = true;
                    continue;
                }

                finished = true;
                break;
            }

            accumulator.addChunk(BufferUtil.toBuffer(output, 0, read));

            if (maxFrameSize > 0 && accumulator.size() == maxFrameSize)
            {
                if (!getWebSocketCoreSession().isAutoFragment())
                    throw new MessageTooLargeException("Inflated payload exceeded maxFrameSize");
                break;
            }
        }

        // Copy accumulated bytes into a big buffer and release the buffer when callback is completed.
        final ByteBuffer payload = accumulator.getBytes(getBufferPool());
        callback = Callback.from(callback, ()->getBufferPool().release(payload));

        Frame chunk = new Frame(first ? frame.getOpCode() : OpCode.CONTINUATION);
        chunk.setRsv1(false);
        chunk.setPayload(payload);
        chunk.setFin(frame.isFin() && finished);
        nextIncomingFrame(chunk, callback);

        if (LOG.isDebugEnabled())
            LOG.debug("Decompress: exiting {}", toDetail(inflater));

        return finished;
    }

    private boolean compress(Frame frame, Callback callback, boolean batch, boolean first)
    {
        // Get a chunk of the payload to avoid to blow
        // the heap if the payload is a huge mapped file.
        ByteBuffer data = frame.getPayload();
        int remaining = data.remaining();
        long maxFrameSize = getWebSocketCoreSession().getMaxFrameSize();
        if (LOG.isDebugEnabled())
            LOG.debug("Compressing remaining {} bytes of {} ", remaining, frame);

        // Get Deflater and provide payload as input if this is the first time.
        Deflater deflater = getDeflater();
        if (first)
            deflater.setInput(data);

        // Compress the payload.
        boolean finished = false;
        ByteAccumulator accumulator = new ByteAccumulator(getWebSocketCoreSession().getMaxFrameSize());
        while (true)
        {
            int bufferSize = COMPRESS_BUFFER_SIZE;
            if (maxFrameSize > 0)
                bufferSize = (int)Math.min(maxFrameSize - accumulator.size(), COMPRESS_BUFFER_SIZE);

            byte[] output = new byte[bufferSize];
            int compressed = deflater.deflate(output, 0, output.length, Deflater.SYNC_FLUSH);
            if (LOG.isDebugEnabled())
                LOG.debug("Compressed {} bytes", compressed);

            if (compressed <= 0)
            {
                finished = true;
                break;
            }

            accumulator.addChunk(BufferUtil.toBuffer(output, 0, compressed));

            if (maxFrameSize > 0 && accumulator.size() == maxFrameSize)
            {
                // We can fragment if autoFragment is set to true and we are not doing per frame compression.
                if (!getWebSocketCoreSession().isAutoFragment() || compressionMode == CompressionMode.FRAME)
                    throw new MessageTooLargeException("Deflated payload exceeded maxFrameSize");
                break;
            }
        }

        final ByteBuffer payload;
        if (accumulator.size() > 0)
        {
            // Copy accumulated bytes into a big buffer and release the buffer when callback is completed.
            payload = accumulator.getBytes(getBufferPool());
            callback = Callback.from(callback, ()->getBufferPool().release(payload));

            // Handle tail bytes generated by SYNC_FLUSH.
            switch (compressionMode)
            {
                case MESSAGE:
                    if (finished && frame.isFin() && endsWithTail(payload))
                    {
                        payload.limit(payload.limit() - TAIL_BYTES.length);
                        if (LOG.isDebugEnabled())
                            LOG.debug("payload (TAIL_DROP_FIN_ONLY) = {}", BufferUtil.toDetailString(payload));
                    }
                    break;

                case FRAME:
                    if (endsWithTail(payload))
                    {
                        payload.limit(payload.limit() - TAIL_BYTES.length);
                        if (LOG.isDebugEnabled())
                            LOG.debug("payload (TAIL_DROP_ALWAYS) = {}", BufferUtil.toDetailString(payload));
                    }
                    break;

                default:
                    throw new IllegalStateException();
            }
        }
        else if (frame.isFin())
        {
            // Special case: 7.2.3.6.  Generating an Empty Fragment Manually
            // https://tools.ietf.org/html/rfc7692#section-7.2.3.6
            payload = ByteBuffer.wrap(new byte[]{0x00});
        }
        else
        {
            payload = BufferUtil.EMPTY_BUFFER;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Compressed {}: payload:{}", frame, payload.remaining());

        Frame chunk = new Frame(first ? frame.getOpCode() : OpCode.CONTINUATION);
        chunk.setRsv1((first && frame.getOpCode() != OpCode.CONTINUATION) || compressionMode == CompressionMode.FRAME);
        chunk.setPayload(payload);
        chunk.setFin(frame.isFin() && finished);

        nextOutgoingFrame(chunk, callback, batch);
        return finished;
    }

    private static String toDetail(Inflater inflater)
    {
        return String.format("Inflater[finished=%b,read=%d,written=%d,remaining=%d,in=%d,out=%d]", inflater.finished(), inflater.getBytesRead(),
            inflater.getBytesWritten(), inflater.getRemaining(), inflater.getTotalIn(), inflater.getTotalOut());
    }

    private static String toDetail(Deflater deflater)
    {
        return String.format("Deflater[finished=%b,read=%d,written=%d,in=%d,out=%d]", deflater.finished(), deflater.getBytesRead(), deflater.getBytesWritten(),
            deflater.getTotalIn(), deflater.getTotalOut());
    }

    public static boolean endsWithTail(ByteBuffer buf)
    {
        if ((buf == null) || (buf.remaining() < TAIL_BYTES.length))
        {
            return false;
        }
        int limit = buf.limit();
        for (int i = TAIL_BYTES.length; i > 0; i--)
        {
            if (buf.get(limit - i) != TAIL_BYTES[TAIL_BYTES.length - i])
            {
                return false;
            }
        }
        return true;
    }

    public Deflater getDeflater()
    {
        if (deflaterImpl == null)
            deflaterImpl = getDeflaterPool().acquire();
        return deflaterImpl;
    }

    public Inflater getInflater()
    {
        if (inflaterImpl == null)
            inflaterImpl = getInflaterPool().acquire();
        return inflaterImpl;
    }

    public void releaseInflater()
    {
        getInflaterPool().release(inflaterImpl);
        inflaterImpl = null;
    }

    public void releaseDeflater()
    {
        getDeflaterPool().release(deflaterImpl);
        deflaterImpl = null;
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName();
    }
}
