//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.WritePendingException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.util.compression.DeflaterPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.http.CompressedContentFormat.GZIP;

public class GzipResponse extends Response.Wrapper
{
    public static Logger LOG = LoggerFactory.getLogger(GzipResponse.class);

    // Per RFC-1952 this is the "unknown" OS value byte.
    private static final byte OS_UNKNOWN = (byte)0xFF;
    private static final byte[] GZIP_HEADER = new byte[]{
        (byte)0x1f, (byte)0x8b, Deflater.DEFLATED, 0, 0, 0, 0, 0, 0, OS_UNKNOWN
    };
    // Per RFC-1952, the GZIP trailer is 8 bytes
    public static final int GZIP_TRAILER_SIZE = 8;


    public static final HttpField VARY_ACCEPT_ENCODING = new PreEncodedHttpField(HttpHeader.VARY, HttpHeader.ACCEPT_ENCODING.asString());

    private enum GZState
    {
        MIGHT_COMPRESS, NOT_COMPRESSING, COMMITTING, COMPRESSING, FINISHING, FINISHED
    }

    private final AtomicReference<GZState> _state = new AtomicReference<>(GZState.MIGHT_COMPRESS);
    private final CRC32 _crc = new CRC32();

    private final GzipFactory _factory;
    private final HttpField _vary;
    private final int _bufferSize;
    private final boolean _syncFlush;

    private DeflaterPool.Entry _deflaterEntry;
    private ByteBuffer _buffer;

    public GzipResponse(Request request, Response wrapped, GzipFactory factory, HttpField vary, int bufferSize, boolean syncFlush)
    {
        super(request, wrapped);

        _factory = factory;
        _vary = vary;
        _bufferSize = bufferSize;
        _syncFlush = syncFlush;
    }

    @Override
    public void write(boolean last, ByteBuffer content, Callback callback)
    {
        switch (_state.get())
        {
            case MIGHT_COMPRESS -> commit(last, callback, content);
            case NOT_COMPRESSING -> super.write(last, content, callback);
            case COMMITTING -> callback.failed(new WritePendingException());
            case COMPRESSING -> gzip(last, callback, content);
            default -> callback.failed(new IllegalStateException("state=" + _state.get()));
        }
    }

    private void addTrailer()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("addTrailer: _crc={}, _totalIn={})", _crc.getValue(), _deflaterEntry.get().getTotalIn());
        _buffer.putInt((int)_crc.getValue());
        _buffer.putInt(_deflaterEntry.get().getTotalIn());
    }

    private void gzip(boolean complete, final Callback callback, ByteBuffer content)
    {
        if (content != null || complete)
            new GzipBufferCB(complete, callback, content).iterate();
        else
            callback.succeeded();
    }

    protected void commit(boolean last, Callback callback, ByteBuffer content)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("commit(last={}, callback={}, content={})", last, callback, BufferUtil.toDetailString(content));

        // Are we excluding because of status?
        Response response = GzipResponse.this;
        Request request = response.getRequest();
        int sc = response.getStatus();
        if (sc > 0 && (sc < 200 || sc == 204 || sc == 205 || sc >= 300))
        {
            LOG.debug("{} exclude by status {}", this, sc);
            noCompression();

            if (sc == HttpStatus.NOT_MODIFIED_304)
            {
                String requestEtags = (String)request.getAttribute(GzipHandler.GZIP_HANDLER_ETAGS);
                String responseEtag = response.getHeaders().get(HttpHeader.ETAG);
                if (requestEtags != null && responseEtag != null)
                {
                    String responseEtagGzip = etagGzip(responseEtag);
                    if (requestEtags.contains(responseEtagGzip))
                        response.getHeaders().put(HttpHeader.ETAG, responseEtagGzip);
                    if (_vary != null)
                        response.getHeaders().ensureField(_vary);
                }
            }

            super.write(last, content, callback);
            return;
        }

        // Are we excluding because of mime-type?
        String ct = response.getHeaders().get(HttpHeader.CONTENT_TYPE);
        if (ct != null)
        {
            String baseType = HttpField.valueParameters(ct, null);
            if (!_factory.isMimeTypeGzipable(baseType))
            {
                LOG.debug("{} exclude by mimeType {}", this, ct);
                noCompression();
                super.write(last, content, callback);
                return;
            }
        }

        // Has the Content-Encoding header already been set?
        HttpFields.Mutable fields = response.getHeaders();
        String ce = fields.get(HttpHeader.CONTENT_ENCODING);
        if (ce != null)
        {
            LOG.debug("{} exclude by content-encoding {}", this, ce);
            noCompression();
            super.write(last, content, callback);
            return;
        }

        // Are we the thread that commits?
        if (_state.compareAndSet(GZState.MIGHT_COMPRESS, GZState.COMMITTING))
        {
            // We are varying the response due to accept encoding header.
            if (_vary != null)
                fields.ensureField(_vary);

            long contentLength = response.getHeaders().getLongField(HttpHeader.CONTENT_LENGTH);
            if (contentLength < 0 && last)
                contentLength = BufferUtil.length(content);

            _deflaterEntry = _factory.getDeflaterEntry(request, contentLength);
            if (_deflaterEntry == null)
            {
                LOG.debug("{} exclude no deflater", this);
                _state.set(GZState.NOT_COMPRESSING);
                super.write(last, content, callback);
                return;
            }

            fields.put(GZIP.getContentEncoding());
            _crc.reset();

            // Adjust headers
            response.getHeaders().remove(HttpHeader.CONTENT_LENGTH);
            String etag = fields.get(HttpHeader.ETAG);
            if (etag != null)
                fields.put(HttpHeader.ETAG, etagGzip(etag));

            LOG.debug("{} compressing {}", this, _deflaterEntry);
            _state.set(GZState.COMPRESSING);

            if (BufferUtil.isEmpty(content))
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
        private final ByteBuffer _content;
        private final boolean _last;

        public GzipBufferCB(boolean complete, Callback callback, ByteBuffer content)
        {
            super(callback);
            _content = content;
            _last = complete;

            if (_content != null)
            {
                _crc.update(_content.slice());
                Deflater deflater = _deflaterEntry.get();
                deflater.setInput(_content);
            }

            if (LOG.isDebugEnabled())
                LOG.debug("GzipBufferCB(complete={}, callback={}, content={})", complete, callback, BufferUtil.toDetailString(content));
        }

        @Override
        protected void onCompleteFailure(Throwable x)
        {
            if (_deflaterEntry != null)
            {
                _deflaterEntry.release();
                _deflaterEntry = null;
            }
            super.onCompleteFailure(x);
        }

        @Override
        protected Action process() throws Exception
        {
            if (LOG.isDebugEnabled())
                LOG.debug("GzipBufferCB.process(): _last={}, _buffer={}, _content={}", _last, BufferUtil.toDetailString(_buffer), BufferUtil.toDetailString(_content));

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

            // If we have no buffer
            if (_buffer == null)
            {
                _buffer = getRequest().getComponents().getByteBufferPool().acquire(_bufferSize, false);
                // Per RFC-1952, GZIP is LITTLE_ENDIAN
                _buffer.order(ByteOrder.LITTLE_ENDIAN);
                BufferUtil.flipToFill(_buffer);
                // Add GZIP Header
                _buffer.put(GZIP_HEADER, 0, GZIP_HEADER.length);
            }
            else
            {
                // otherwise clear the buffer as previous writes will always fully consume.
                BufferUtil.clearToFill(_buffer);
            }

            Deflater deflater = _deflaterEntry.get();

            return switch (gzstate)
            {
                case COMPRESSING -> compressing(deflater, _buffer);
                case FINISHING -> finishing(deflater, _buffer);
                default -> throw new IllegalStateException("Unexpected state [" + _state.get() + "]");
            };
        }

        private void cleanup()
        {
            if (_deflaterEntry != null)
            {
                _deflaterEntry.release();
                _deflaterEntry = null;
            }

            if (_buffer != null)
            {
                getRequest().getComponents().getByteBufferPool().release(_buffer);
                _buffer = null;
            }
        }

        private int getFlushMode()
        {
            return _syncFlush ? Deflater.SYNC_FLUSH : Deflater.NO_FLUSH;
        }

        private Action compressing(Deflater deflater, ByteBuffer outputBuffer)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("compressing() deflater={}, outputBuffer={}", deflater, BufferUtil.toDetailString(outputBuffer));

            if (!deflater.finished())
            {
                if (!deflater.needsInput())
                {
                    int len = deflater.deflate(outputBuffer, getFlushMode());
                    if (len > 0)
                    {
                        write(false, outputBuffer);
                        return Action.SCHEDULED;
                    }
                    // Optimization to (hopefully) preserve Content-Length.
                    // Has the entire last content been consumed?
                    else if (BufferUtil.isEmpty(_content) && _last)
                    {
                        _state.set(GZState.FINISHING);
                        deflater.finish();
                        return finishing(deflater, outputBuffer);
                    }
                }
            }

            if (outputBuffer.position() > 0)
            {
                write(false, outputBuffer);
                return Action.SCHEDULED;
            }

            if (_last)
            {
                _state.set(GZState.FINISHING);
                deflater.finish();
                write(false, null);
                return Action.SCHEDULED;
            }

            if (BufferUtil.isEmpty(_content))
                return Action.SUCCEEDED; // this GzipBufferCB is fully consumed as input to the Deflater instance

            return Action.SCHEDULED;
        }

        private Action finishing(Deflater deflater, ByteBuffer outputBuffer)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("finishing() deflater={}, outputBuffer={}", deflater, BufferUtil.toDetailString(outputBuffer));
            if (!deflater.finished())
            {
                int len = deflater.deflate(outputBuffer, getFlushMode());
                if (deflater.finished() && len <= outputBuffer.remaining() - GZIP_TRAILER_SIZE)
                {
                    _state.set(GZState.FINISHED);
                    addTrailer();
                    write(true, outputBuffer);
                    return Action.SCHEDULED;
                }

                if (len > 0)
                {
                    write(false, outputBuffer);
                    return Action.SCHEDULED;
                }
            }
            else
            {
                _state.set(GZState.FINISHED);
                addTrailer();
                write(true, outputBuffer);
                return Action.SCHEDULED;
            }

            return Action.SCHEDULED;
        }

        private void write(boolean last, ByteBuffer outputBuffer)
        {
            if (outputBuffer != null)
                BufferUtil.flipToFlush(outputBuffer, 0);
            if (LOG.isDebugEnabled())
                LOG.debug("write() last={}, outputBuffer={}", last, BufferUtil.toDetailString(outputBuffer));
            GzipResponse.super.write(last, outputBuffer, this);
        }

        @Override
        public String toString()
        {
            return String.format("%s[content=%s last=%b buffer=%s deflate=%s %s]",
                super.toString(),
                BufferUtil.toDetailString(_content),
                _last,
                BufferUtil.toDetailString(_buffer),
                _deflaterEntry,
                _state.get());
        }
    }
}
