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

package org.eclipse.jetty.server.handler.gzip;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.channels.WritePendingException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.util.compression.DeflaterPool;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.http.CompressedContentFormat.GZIP;

public class GzipResponseAndCallback extends Response.Wrapper implements Callback, Invocable
{
    private static final Logger LOG = LoggerFactory.getLogger(GzipResponseAndCallback.class);

    // Per RFC-1952 this is the "unknown" OS value byte.
    private static final byte OS_UNKNOWN = (byte)0xFF;
    private static final byte[] GZIP_HEADER = new byte[]{
        (byte)0x1f, (byte)0x8b, Deflater.DEFLATED, 0, 0, 0, 0, 0, 0, OS_UNKNOWN
    };
    // Per RFC-1952, the GZIP trailer is 8 bytes
    private static final int GZIP_TRAILER_SIZE = 8;

    private enum GZState
    {
        // first state, indicating that content might be compressed, pending the state of the response
        MIGHT_COMPRESS,
        // the response is not being compressed (this is a final state)
        NOT_COMPRESSING,
        // The response is being committed (no changes to compress state can be made at this point)
        COMMITTING,
        // The response is compressing its body content
        COMPRESSING,
        // The last content has is being compressed and deflater is being flushed
        FINISHING,
        // The content has finished compressing and trailers have been sent (this is a final state)
        FINISHED
    }

    private final AtomicReference<GZState> _state = new AtomicReference<>(GZState.MIGHT_COMPRESS);
    private final CRC32 _crc = new CRC32();
    private final Callback _callback;
    private final GzipFactory _factory;
    private final int _bufferSize;
    private final boolean _syncFlush;
    private DeflaterPool.Entry _deflaterEntry;
    private RetainableByteBuffer.Mutable _buffer;
    private boolean _last;

    public GzipResponseAndCallback(GzipHandler handler, Request request, Response response, Callback callback)
    {
        super(request, response);
        _callback = callback;
        _factory = handler;
        _bufferSize = Math.max(GZIP_HEADER.length + GZIP_TRAILER_SIZE, request.getConnectionMetaData().getHttpConfiguration().getOutputBufferSize());
        _syncFlush = handler.isSyncFlush();
    }

    @Override
    public void succeeded()
    {
        // We need to write nothing here to intercept the committing of the
        // response and possibly change headers in case write is never called.
        if (_last)
            _callback.succeeded();
        else
            write(true, null, _callback);
    }

    @Override
    public void failed(Throwable x)
    {
        _callback.failed(x);
    }

    @Override
    public InvocationType getInvocationType()
    {
        return _callback.getInvocationType();
    }

    @Override
    public void write(boolean last, RetainableByteBuffer buffer, Callback callback)
    {
        _last = last;
        switch (_state.get())
        {
            case MIGHT_COMPRESS -> commit(last, callback, buffer);
            case NOT_COMPRESSING -> super.write(last, buffer, callback);
            case COMMITTING -> callback.failed(new WritePendingException());
            case COMPRESSING -> gzip(last, callback, buffer);
            default ->
            {
                if (buffer == null || !buffer.hasRemaining())
                    callback.succeeded();
                else
                    callback.failed(new IllegalStateException("state=" + _state.get()));
            }
        }
    }

    private void addTrailer(RetainableByteBuffer.Mutable outputBuffer)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("addTrailer: _crc={}, _totalIn={})", _crc.getValue(), _deflaterEntry.get().getTotalIn());
        outputBuffer.putInt((int)_crc.getValue());
        outputBuffer.putInt(_deflaterEntry.get().getTotalIn());
    }

    private void gzip(boolean complete, final Callback callback, RetainableByteBuffer content)
    {
        if (content != null || complete)
            new GzipBufferCB(complete, callback, content).iterate();
        else
            callback.succeeded();
    }

    protected void commit(boolean last, Callback callback, RetainableByteBuffer content)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("commit(last={}, callback={}, content={})", last, callback, content);

        Request request = getRequest();
        Response response = this;
        HttpFields.Mutable fields = response.getHeaders();

        // Are we excluding because of status?
        int sc = response.getStatus();
        if (sc > 0 && (sc < 200 || sc == 204 || sc == 205 || sc >= 300))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} exclude by status {}", this, sc);
            noCompression();

            if (sc == HttpStatus.NOT_MODIFIED_304)
            {
                String requestEtags = (String)request.getAttribute(GzipHandler.GZIP_HANDLER_ETAGS);
                String responseEtag = fields.get(HttpHeader.ETAG);
                if (requestEtags != null && responseEtag != null)
                {
                    String responseEtagGzip = etagGzip(responseEtag);
                    if (requestEtags.contains(responseEtagGzip))
                        fields.put(HttpHeader.ETAG, responseEtagGzip);
                }
            }

            super.write(last, content, callback);
            return;
        }

        // Are we excluding because of mime-type?
        String ct = fields.get(HttpHeader.CONTENT_TYPE);
        if (ct != null)
        {
            String baseType = HttpField.getValueParameters(ct, null);
            if (!_factory.isMimeTypeDeflatable(baseType))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} exclude by mimeType {}", this, ct);
                noCompression();
                super.write(last, content, callback);
                return;
            }
        }

        // Has the Content-Encoding header already been set?
        String ce = fields.get(HttpHeader.CONTENT_ENCODING);
        if (ce != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} exclude by content-encoding {}", this, ce);
            noCompression();
            super.write(last, content, callback);
            return;
        }

        // If there is nothing to write, don't compress.
        if (last && (content == null || !content.hasRemaining()))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} exclude by nothing to write", this);
            noCompression();
            super.write(true, content, callback);
            return;
        }

        // Are we the thread that commits?
        if (_state.compareAndSet(GZState.MIGHT_COMPRESS, GZState.COMMITTING))
        {
            long contentLength = fields.getLongField(HttpHeader.CONTENT_LENGTH);
            if (contentLength < 0 && last)
                contentLength = content.remaining();

            _deflaterEntry = _factory.getDeflaterEntry(request, contentLength);
            if (_deflaterEntry == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} exclude no deflater", this);
                _state.set(GZState.NOT_COMPRESSING);
                super.write(last, content, callback);
                return;
            }

            fields.put(GZIP.getContentEncoding());
            _crc.reset();

            // Adjust headers
            fields.remove(HttpHeader.CONTENT_LENGTH);
            String etag = fields.get(HttpHeader.ETAG);
            if (etag != null)
                fields.put(HttpHeader.ETAG, etagGzip(etag));

            if (LOG.isDebugEnabled())
                LOG.debug("{} compressing {}", this, _deflaterEntry);
            _state.set(GZState.COMPRESSING);

            if (content == null || !content.hasRemaining())
            {
                // We are committing, but have no content to compress, so flush empty buffer to write headers.
                super.write(last, content, callback);
            }
            else
            {
                gzip(last, callback, content);
            }
        }
        else
        {
            callback.failed(new WritePendingException());
        }
    }

    private String etagGzip(String etag)
    {
        return GZIP.etag(etag);
    }

    public void noCompression()
    {
        while (true)
        {
            switch (_state.get())
            {
                case NOT_COMPRESSING:
                    return;

                case MIGHT_COMPRESS:
                    if (_state.compareAndSet(GZState.MIGHT_COMPRESS, GZState.NOT_COMPRESSING))
                        return;
                    break;

                default:
                    throw new IllegalStateException(_state.get().toString());
            }
        }
    }

    private class GzipBufferCB extends IteratingNestedCallback
    {
        private final RetainableByteBuffer _content;
        private final boolean _last;

        public GzipBufferCB(boolean complete, Callback callback, RetainableByteBuffer content)
        {
            super(callback);
            _content = content == null ? RetainableByteBuffer.empty() : content;
            _last = complete;
            if (LOG.isDebugEnabled())
                LOG.debug("GzipBufferCB(complete={}, callback={}, content={})", complete, callback, content);
        }

        @Override
        protected Action process() throws Exception
        {
            if (LOG.isDebugEnabled())
                LOG.debug("GzipBufferCB.process(): _last={}, _buffer={}, _content={}", _last, _buffer, _content);

            GZState gzstate = _state.get();

            // Are we finished?
            if (gzstate == GZState.FINISHED)
            {
                // then the trailer has been generated and written below.
                // We have finished compressing the entire content, so
                // cleanup and succeed.
                cleanup();
                return Action.SUCCEEDED;
            }

            if (_buffer == null)
            {
                _buffer = WritableBufferPool.wrap(getRequest().getComponents().getByteBufferPool()).acquire(_bufferSize, false);
                // Per RFC-1952, GZIP is LITTLE_ENDIAN.
                _buffer.byteOrder(ByteOrder.LITTLE_ENDIAN);
                // Add GZIP Header.
                _buffer.put(GZIP_HEADER);
            }
            else
            {
                // Clear the buffer as previous writes will always fully consume.
                _buffer.clear();
            }

            Deflater deflater = _deflaterEntry.get();

            return switch (gzstate)
            {
                case COMPRESSING -> compressing(deflater, _buffer);
                case FINISHING -> finishing(deflater, _buffer);
                default -> throw new IllegalStateException("Unexpected state [" + _state.get() + "]");
            };
        }

        @Override
        protected void onCompleteFailure(Throwable x)
        {
            cleanup();
            super.onCompleteFailure(x);
        }

        private void cleanup()
        {
            if (_deflaterEntry != null)
            {
                _state.set(GZState.FINISHED);
                _deflaterEntry.release();
                _deflaterEntry = null;
            }
            _buffer = Retainable.dispose(_buffer);
        }

        private int getFlushMode()
        {
            return _syncFlush ? Deflater.SYNC_FLUSH : Deflater.NO_FLUSH;
        }

        /**
         * This method is called directly from {@link #process()} to perform the compressing of
         * the content this {@link GzipBufferCB} represents.
         */
        private Action compressing(Deflater deflater, RetainableByteBuffer.Mutable outputBuffer) throws IOException
        {
            if (LOG.isDebugEnabled())
                LOG.debug("compressing() deflater={}, outputBuffer={}", deflater, outputBuffer);

            if (!deflater.finished())
            {
                do
                {
                    long len = deflateContent(deflater, outputBuffer);
                    if (len > 0)
                    {
                        write(false, outputBuffer);
                        return Action.SCHEDULED;
                    }
                }
                while (_content.hasRemaining());
            }

            if (_last)
            {
                _state.set(GZState.FINISHING);
                deflater.finish();
                return finishing(deflater, outputBuffer);
            }

            if (outputBuffer.hasRemaining())
            {
                write(false, outputBuffer);
                return Action.SCHEDULED;
            }

            // the content held by GzipBufferCB is fully consumed as input to the Deflater instance, we are done
            if (!_content.hasRemaining())
                return Action.SUCCEEDED;

            // No progress made on deflate, but the _content wasn't consumed, we shouldn't be able to reach this.
            throw new AssertionError("No progress on deflate made for " + this);
        }

        /**
         * This method is called by {@link #compressing(Deflater, RetainableByteBuffer.Mutable)}, once the last chunk is compressed;
         * or directly from {@link #process()} if an earlier call to this method was unable to complete.
         */
        private Action finishing(Deflater deflater, RetainableByteBuffer.Mutable outputBuffer) throws IOException
        {
            if (LOG.isDebugEnabled())
                LOG.debug("finishing() deflater={}, outputBuffer={}", deflater, outputBuffer);
            if (!deflater.finished())
            {
                do
                {
                    long len = deflateContent(deflater, outputBuffer);

                    // try to preserve single write if possible (header + compressed content + trailer)
                    if (deflater.finished() && outputBuffer.space() >= GZIP_TRAILER_SIZE)
                    {
                        _state.set(GZState.FINISHED);
                        addTrailer(outputBuffer);
                        write(true, outputBuffer);
                        return Action.SCHEDULED;
                    }

                    if (len > 0)
                    {
                        write(false, outputBuffer);
                        return Action.SCHEDULED;
                    }
                }
                while (_content.hasRemaining());

                // No progress made on deflate, deflater not finished, we shouldn't be able to reach this.
                throw new AssertionError("No progress on deflate made for " + this);
            }
            else
            {
                _state.set(GZState.FINISHED);
                addTrailer(outputBuffer);
                write(true, outputBuffer);
                return Action.SCHEDULED;
            }
        }

        private long deflateContent(Deflater deflater, RetainableByteBuffer.Mutable outputBuffer) throws IOException
        {
            return outputBuffer.readFrom(output ->
            {
                int p = output.position();
                _content.writeTo(input ->
                {
                    int startPosition = input.position();
                    deflater.setInput(input);
                    deflater.deflate(output, getFlushMode());
                    int endPosition = input.position();

                    // Update CRC with the bytes consumed by the deflater.
                    input.position(startPosition);
                    _crc.update(input);
                    input.position(endPosition);
                    return endPosition - startPosition;
                });
                return output.position() - p;
            });
        }

        private void write(boolean last, RetainableByteBuffer outputBuffer)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("write() last={}, outputBuffer={}", last, outputBuffer);
            GzipResponseAndCallback.super.write(last, outputBuffer, this);
        }

        @Override
        public String toString()
        {
            return String.format("%s[content=%s last=%b buffer=%s deflate=%s %s]",
                super.toString(),
                _content,
                _last,
                _buffer,
                _deflaterEntry,
                _state.get());
        }
    }
}
