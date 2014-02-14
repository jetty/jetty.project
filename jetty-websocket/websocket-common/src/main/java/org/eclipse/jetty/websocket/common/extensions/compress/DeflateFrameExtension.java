//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.extensions.compress;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ConcurrentArrayQueue;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BadPayloadException;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.extensions.AbstractExtension;
import org.eclipse.jetty.websocket.common.frames.DataFrame;

/**
 * Implementation of the
 * <a href="https://tools.ietf.org/id/draft-tyoshino-hybi-websocket-perframe-deflate.txt">deflate-frame</a>
 * extension seen out in the wild.
 */
public class DeflateFrameExtension extends AbstractExtension
{
    private static final Logger LOG = Log.getLogger(DeflateFrameExtension.class);
    private static final byte[] TAIL_BYTES = new byte[]{0x00, 0x00, (byte)0xFF, (byte)0xFF};

    private final Queue<FrameEntry> entries = new ConcurrentArrayQueue<>();
    private final IteratingCallback flusher = new Flusher();
    private final Deflater compressor;
    private final Inflater decompressor;

    public DeflateFrameExtension()
    {
        compressor = new Deflater(Deflater.BEST_COMPRESSION, true);
        decompressor = new Inflater(true);
    }

    @Override
    public String getName()
    {
        return "deflate-frame";
    }

    @Override
    public void incomingFrame(Frame frame)
    {
        // Incoming frames are always non concurrent because
        // they are read and parsed with a single thread, and
        // therefore there is no need for synchronization.

        if (OpCode.isControlFrame(frame.getOpCode()) || !frame.isRsv1())
        {
            // Cannot modify incoming control frames or ones without RSV1 set.
            nextIncomingFrame(frame);
            return;
        }

        if (!frame.hasPayload())
        {
            // No payload ? Nothing to do.
            nextIncomingFrame(frame);
            return;
        }

        ByteBuffer payload = frame.getPayload();
        int remaining = payload.remaining();
        byte[] input = new byte[remaining + TAIL_BYTES.length];
        payload.get(input, 0, remaining);
        System.arraycopy(TAIL_BYTES, 0, input, remaining, TAIL_BYTES.length);

        // Since we don't track text vs binary vs continuation state, just grab whatever is the greater value.
        int maxSize = Math.max(getPolicy().getMaxTextMessageSize(), getPolicy().getMaxBinaryMessageBufferSize());
        ByteAccumulator accumulator = new ByteAccumulator(maxSize);

        DataFrame out = new DataFrame(frame);
        // Unset RSV1 since it's not compressed anymore.
        out.setRsv1(false);

        decompressor.setInput(input, 0, input.length);

        try
        {
            while (decompressor.getRemaining() > 0)
            {
                byte[] output = new byte[Math.min(remaining * 2, 64 * 1024)];
                int len = decompressor.inflate(output);
                if (len == 0)
                {
                    if (decompressor.needsInput())
                    {
                        throw new BadPayloadException("Unable to inflate frame, not enough input on frame");
                    }
                    if (decompressor.needsDictionary())
                    {
                        throw new BadPayloadException("Unable to inflate frame, frame erroneously says it needs a dictionary");
                    }
                }
                else
                {
                    accumulator.addChunk(output, 0, len);
                }
            }
        }
        catch (DataFormatException x)
        {
            throw new BadPayloadException(x);
        }

        ByteBuffer buffer = getBufferPool().acquire(accumulator.getLength(), false);
        try
        {
            BufferUtil.flipToFill(buffer);
            accumulator.transferTo(buffer);
            out.setPayload(buffer);
            nextIncomingFrame(out);
        }
        finally
        {
            getBufferPool().release(buffer);
        }
    }

    /**
     * Indicates use of RSV1 flag for indicating deflation is in use.
     * <p/>
     * Also known as the "COMP" framing header bit
     */
    @Override
    public boolean isRsv1User()
    {
        return true;
    }

    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback)
    {
        if (flusher.isFailed())
        {
            if (callback != null)
                callback.writeFailed(new ZipException());
            return;
        }

        FrameEntry entry = new FrameEntry(frame, callback);
        LOG.debug("Queuing {}", entry);
        entries.offer(entry);
        flusher.iterate();
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName();
    }

    private static class FrameEntry
    {
        private final Frame frame;
        private final WriteCallback callback;

        private FrameEntry(Frame frame, WriteCallback callback)
        {
            this.frame = frame;
            this.callback = callback;
        }

        @Override
        public String toString()
        {
            return frame.toString();
        }
    }

    private class Flusher extends IteratingCallback implements WriteCallback
    {
        private FrameEntry current;
        private int inputLength = 64 * 1024;
        private ByteBuffer payload;
        private boolean finished = true;

        @Override
        protected Action process() throws Exception
        {
            if (finished)
            {
                current = entries.poll();
                LOG.debug("Processing {}", current);
                if (current == null)
                    return Action.IDLE;
                deflate(current);
            }
            else
            {
                compress(current.frame);
            }
            return Action.SCHEDULED;
        }

        private void deflate(FrameEntry entry)
        {
            Frame frame = entry.frame;
            if (OpCode.isControlFrame(frame.getOpCode()))
            {
                // Skip, cannot compress control frames.
                nextOutgoingFrame(frame, this);
                return;
            }

            if (!frame.hasPayload())
            {
                // Pass through, nothing to do
                nextOutgoingFrame(frame, this);
                return;
            }

            compress(frame);
        }

        private void compress(Frame frame)
        {
            // Get a chunk of the payload to avoid to blow
            // the heap if the payload is a huge mapped file.
            ByteBuffer data = frame.getPayload();
            int remaining = data.remaining();
            byte[] input = new byte[Math.min(remaining, inputLength)];
            int length = Math.min(remaining, input.length);
            LOG.debug("Compressing {}: {} bytes in {} bytes chunk", frame, remaining, length);
            finished = length == remaining;
            data.get(input, 0, length);

            compressor.setInput(input, 0, length);

            // Use an additional space in case the content is not compressible.
            byte[] output = new byte[length + 64];
            int offset = 0;
            int total = 0;
            while (true)
            {
                int space = output.length - offset;
                int compressed = compressor.deflate(output, offset, space, Deflater.SYNC_FLUSH);
                total += compressed;
                if (compressed < space)
                {
                    // Everything was compressed.
                    break;
                }
                else
                {
                    // The compressed output is bigger than the uncompressed input.
                    byte[] newOutput = new byte[output.length * 2];
                    System.arraycopy(output, 0, newOutput, 0, output.length);
                    offset += output.length;
                    output = newOutput;
                }
            }

            payload = getBufferPool().acquire(total, true);
            BufferUtil.flipToFill(payload);
            // Skip the last tail bytes bytes generated by SYNC_FLUSH
            payload.put(output, 0, total - TAIL_BYTES.length).flip();
            LOG.debug("Compressed {}: {}->{} chunk bytes", frame, length, total);

            DataFrame chunk = new DataFrame(frame);
            chunk.setRsv1(true);
            chunk.setPayload(payload);
            chunk.setFin(finished);

            nextOutgoingFrame(chunk, this);
        }

        @Override
        protected void completed()
        {
            // This IteratingCallback never completes.
        }

        @Override
        public void writeSuccess()
        {
            getBufferPool().release(payload);
            if (finished)
                notifyCallbackSuccess(current.callback);
            succeeded();
        }

        @Override
        public void writeFailed(Throwable x)
        {
            getBufferPool().release(payload);
            notifyCallbackFailure(current.callback, x);
            // If something went wrong, very likely the compression context
            // will be invalid, so we need to fail this IteratingCallback.
            failed(x);
            // Now no more frames can be queued, fail those in the queue.
            FrameEntry entry;
            while ((entry = entries.poll()) != null)
                notifyCallbackFailure(entry.callback, x);
        }

        private void notifyCallbackSuccess(WriteCallback callback)
        {
            try
            {
                if (callback != null)
                    callback.writeSuccess();
            }
            catch (Throwable x)
            {
                LOG.debug("Exception while notifying success of callback " + callback, x);
            }
        }

        private void notifyCallbackFailure(WriteCallback callback, Throwable failure)
        {
            try
            {
                if (callback != null)
                    callback.writeFailed(failure);
            }
            catch (Throwable x)
            {
                LOG.debug("Exception while notifying failure of callback " + callback, x);
            }
        }
    }
}
