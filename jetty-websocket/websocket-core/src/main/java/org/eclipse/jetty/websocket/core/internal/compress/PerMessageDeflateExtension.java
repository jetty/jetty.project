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
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.AbstractExtension;
import org.eclipse.jetty.websocket.core.BadPayloadException;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.MessageTooLargeException;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.ProtocolException;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.internal.TransformingFlusher;

/**
 * Per Message Deflate Compression extension for WebSocket.
 * <p>
 * Attempts to follow <a href="https://tools.ietf.org/html/rfc7692">Compression Extensions for WebSocket</a>
 */
public class PerMessageDeflateExtension extends AbstractExtension
{
    private static final byte[] TAIL_BYTES = new byte[]{0x00, 0x00, (byte)0xFF, (byte)0xFF};
    private static final ByteBuffer TAIL_BYTES_BUF = ByteBuffer.wrap(TAIL_BYTES);
    private static final Logger LOG = Log.getLogger(PerMessageDeflateExtension.class);

    private static final int COMPRESS_BUFFER_SIZE = 8 * 1024;
    private static final int DECOMPRESS_BUF_SIZE = 8 * 1024;

    private final TransformingFlusher outgoingFlusher;
    private final TransformingFlusher incomingFlusher;
    private Deflater deflaterImpl;
    private Inflater inflaterImpl;
    private boolean incomingCompressed;

    private ExtensionConfig configRequested;
    private ExtensionConfig configNegotiated;
    private boolean incomingContextTakeover = true;
    private boolean outgoingContextTakeover = true;

    public PerMessageDeflateExtension()
    {
        outgoingFlusher = new OutgoingFlusher();
        incomingFlusher = new IncomingFlusher();
    }

    @Override
    public String getName()
    {
        return "permessage-deflate";
    }

    @Override
    public boolean isRsv1User()
    {
        return true;
    }

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
        long maxFrameSize = getWebSocketCoreSession().getMaxFrameSize();

        // Decompress the payload.
        Inflater inflater = getInflater();
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
                if (!finished && frame.isFin())
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

        // Compress the payload.
        Deflater deflater = getDeflater();
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
                if (!getWebSocketCoreSession().isAutoFragment())
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
            if (finished && frame.isFin() && endsWithTail(payload))
            {
                payload.limit(payload.limit() - TAIL_BYTES.length);
                if (LOG.isDebugEnabled())
                    LOG.debug("payload (TAIL_DROP_FIN_ONLY) = {}", BufferUtil.toDetailString(payload));
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
        chunk.setRsv1(first && frame.getOpCode() != OpCode.CONTINUATION);
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
        return String.format("%s[requested=\"%s\", negotiated=\"%s\"]",
            getClass().getSimpleName(),
            configRequested.getParameterizedName(),
            configNegotiated.getParameterizedName());
    }

    @Override
    protected void nextIncomingFrame(Frame frame, Callback callback)
    {
        if (frame.isFin() && !incomingContextTakeover)
        {
            LOG.debug("Incoming Context Reset");
            releaseInflater();
        }
        super.nextIncomingFrame(frame, callback);
    }

    @Override
    protected void nextOutgoingFrame(Frame frame, Callback callback, boolean batch)
    {
        if (frame.isFin() && !outgoingContextTakeover)
        {
            LOG.debug("Outgoing Context Reset");
            releaseDeflater();
        }
        super.nextOutgoingFrame(frame, callback, batch);
    }

    @Override
    public void init(final ExtensionConfig config, WebSocketComponents components)
    {
        configRequested = new ExtensionConfig(config);
        Map<String, String> paramsNegotiated = new HashMap<>();

        for (String key : config.getParameterKeys())
        {
            key = key.trim();
            switch (key)
            {
                case "client_max_window_bits":
                case "server_max_window_bits":
                {
                    // Not supported by Jetty
                    // Don't negotiate these parameters
                    break;
                }
                case "client_no_context_takeover":
                {
                    paramsNegotiated.put("client_no_context_takeover", null);
                    incomingContextTakeover = false;
                    break;
                }
                case "server_no_context_takeover":
                {
                    paramsNegotiated.put("server_no_context_takeover", null);
                    outgoingContextTakeover = false;
                    break;
                }
                default:
                {
                    throw new IllegalArgumentException();
                }
            }
        }

        configNegotiated = new ExtensionConfig(config.getName(), paramsNegotiated);
        LOG.debug("config: outgoingContextTakover={}, incomingContextTakeover={} : {}", outgoingContextTakeover, incomingContextTakeover, this);

        super.init(configNegotiated, components);
    }

    private class OutgoingFlusher extends TransformingFlusher
    {
        private boolean _first;
        private Frame _frame;
        private Callback _callback;
        private boolean _batch;

        @Override
        protected boolean onFrame(Frame frame, Callback callback, boolean batch)
        {
            if (OpCode.isControlFrame(frame.getOpCode()))
            {
                nextOutgoingFrame(frame, callback, batch);
                return true;
            }

            _first = true;
            _frame = frame;
            _callback = callback;
            _batch = batch;

            // Provide the frames payload as input to the Deflater.
            getDeflater().setInput(frame.getPayload());
            return false;
        }

        @Override
        protected boolean transform()
        {
            boolean finished = compress(_frame, _callback, _batch, _first);
            _first = false;
            return finished;
        }
    }

    private class IncomingFlusher extends TransformingFlusher
    {
        private boolean _first;
        private Frame _frame;
        private Callback _callback;

        @Override
        protected boolean onFrame(Frame frame, Callback callback, boolean batch)
        {
            if (OpCode.isControlFrame(frame.getOpCode()))
            {
                nextIncomingFrame(frame, callback);
                return true;
            }

            // This extension requires the RSV1 bit set only in the first frame.
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

            if (_first && !incomingCompressed)
            {
                nextIncomingFrame(frame, callback);
                return true;
            }

            if (frame.isFin())
                incomingCompressed = false;

            _first = true;
            _frame = frame;
            _callback = callback;

            // Provide the frames payload as input to the Inflater.
            getInflater().setInput(frame.getPayload());
            return false;
        }

        @Override
        protected boolean transform()
        {
            try
            {
                return decompress(_frame, _callback, _first);
            }
            catch (DataFormatException e)
            {
                throw new BadPayloadException(e);
            }
        }
    }
}
