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
import java.nio.channels.WritePendingException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.util.compression.DeflaterPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.http.CompressedContentFormat.GZIP;

public class GzipHttpOutputInterceptor implements HttpOutput.Interceptor
{
    public static Logger LOG = LoggerFactory.getLogger(GzipHttpOutputInterceptor.class);
    private static final byte[] GZIP_HEADER = new byte[]{(byte)0x1f, (byte)0x8b, Deflater.DEFLATED, 0, 0, 0, 0, 0, 0, 0};

    public static final HttpField VARY_ACCEPT_ENCODING = new PreEncodedHttpField(HttpHeader.VARY, HttpHeader.ACCEPT_ENCODING.asString());

    private enum GZState
    {
        MIGHT_COMPRESS, NOT_COMPRESSING, COMMITTING, COMPRESSING, FINISHED
    }

    private final AtomicReference<GZState> _state = new AtomicReference<>(GZState.MIGHT_COMPRESS);
    private final CRC32 _crc = new CRC32();

    private final GzipFactory _factory;
    private final HttpOutput.Interceptor _interceptor;
    private final HttpChannel _channel;
    private final HttpField _vary;
    private final int _bufferSize;
    private final boolean _syncFlush;

    private DeflaterPool.Entry _deflaterEntry;
    private ByteBuffer _buffer;

    public GzipHttpOutputInterceptor(GzipFactory factory, HttpChannel channel, HttpOutput.Interceptor next, boolean syncFlush)
    {
        this(factory, VARY_ACCEPT_ENCODING, channel.getHttpConfiguration().getOutputBufferSize(), channel, next, syncFlush);
    }

    public GzipHttpOutputInterceptor(GzipFactory factory, HttpField vary, HttpChannel channel, HttpOutput.Interceptor next, boolean syncFlush)
    {
        this(factory, vary, channel.getHttpConfiguration().getOutputBufferSize(), channel, next, syncFlush);
    }

    public GzipHttpOutputInterceptor(GzipFactory factory, HttpField vary, int bufferSize, HttpChannel channel, HttpOutput.Interceptor next, boolean syncFlush)
    {
        _factory = factory;
        _channel = channel;
        _interceptor = next;
        _vary = vary;
        _bufferSize = bufferSize;
        _syncFlush = syncFlush;
    }

    @Override
    public HttpOutput.Interceptor getNextInterceptor()
    {
        return _interceptor;
    }

    @Override
    public void write(ByteBuffer content, boolean complete, Callback callback)
    {
        switch (_state.get())
        {
            case MIGHT_COMPRESS:
                commit(content, complete, callback);
                break;

            case NOT_COMPRESSING:
                _interceptor.write(content, complete, callback);
                return;

            case COMMITTING:
                callback.failed(new WritePendingException());
                break;

            case COMPRESSING:
                gzip(content, complete, callback);
                break;

            default:
                callback.failed(new IllegalStateException("state=" + _state.get()));
                break;
        }
    }

    private void addTrailer()
    {
        BufferUtil.putIntLittleEndian(_buffer, (int)_crc.getValue());
        BufferUtil.putIntLittleEndian(_buffer, _deflaterEntry.get().getTotalIn());
    }

    private void gzip(ByteBuffer content, boolean complete, final Callback callback)
    {
        if (content.hasRemaining() || complete)
            new GzipBufferCB(content, complete, callback).iterate();
        else
            callback.succeeded();
    }

    protected void commit(ByteBuffer content, boolean complete, Callback callback)
    {
        // Are we excluding because of status?
        Response response = _channel.getResponse();
        int sc = response.getStatus();
        if (sc > 0 && (sc < 200 || sc == 204 || sc == 205 || sc >= 300))
        {
            LOG.debug("{} exclude by status {}", this, sc);
            noCompression();

            if (sc == HttpStatus.NOT_MODIFIED_304)
            {
                String requestEtags = (String)_channel.getRequest().getAttribute(GzipHandler.GZIP_HANDLER_ETAGS);
                String responseEtag = response.getHttpFields().get(HttpHeader.ETAG);
                if (requestEtags != null && responseEtag != null)
                {
                    String responseEtagGzip = etagGzip(responseEtag);
                    if (requestEtags.contains(responseEtagGzip))
                        response.getHttpFields().put(HttpHeader.ETAG, responseEtagGzip);
                }
            }

            _interceptor.write(content, complete, callback);
            return;
        }

        // Are we excluding because of mime-type?
        String ct = response.getContentType();
        if (ct != null)
        {
            String baseType = HttpField.valueParameters(ct, null);
            if (!_factory.isMimeTypeGzipable(baseType))
            {
                LOG.debug("{} exclude by mimeType {}", this, ct);
                noCompression();
                _interceptor.write(content, complete, callback);
                return;
            }
        }

        // Has the Content-Encoding header already been set?
        HttpFields.Mutable fields = response.getHttpFields();
        String ce = fields.get(HttpHeader.CONTENT_ENCODING);
        if (ce != null)
        {
            LOG.debug("{} exclude by content-encoding {}", this, ce);
            noCompression();
            _interceptor.write(content, complete, callback);
            return;
        }

        // Are we the thread that commits?
        if (_state.compareAndSet(GZState.MIGHT_COMPRESS, GZState.COMMITTING))
        {
            // We are varying the response due to accept encoding header.
            if (_vary != null)
                fields.ensureField(_vary);

            long contentLength = response.getContentLength();
            if (contentLength < 0 && complete)
                contentLength = content.remaining();

            _deflaterEntry = _factory.getDeflaterEntry(_channel.getRequest(), contentLength);
            if (_deflaterEntry == null)
            {
                LOG.debug("{} exclude no deflater", this);
                _state.set(GZState.NOT_COMPRESSING);
                _interceptor.write(content, complete, callback);
                return;
            }

            fields.put(GZIP.getContentEncoding());
            _crc.reset();

            // Adjust headers
            response.setContentLength(-1);
            String etag = fields.get(HttpHeader.ETAG);
            if (etag != null)
                fields.put(HttpHeader.ETAG, etagGzip(etag));

            LOG.debug("{} compressing {}", this, _deflaterEntry);
            _state.set(GZState.COMPRESSING);

            if (BufferUtil.isEmpty(content))
            {
                // We are committing, but have no content to compress, so flush empty buffer to write headers.
                _interceptor.write(BufferUtil.EMPTY_BUFFER, complete, callback);
            }
            else
            {
                gzip(content, complete, callback);
            }
        }
        else
            callback.failed(new WritePendingException());
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

    public boolean mightCompress()
    {
        return _state.get() == GZState.MIGHT_COMPRESS;
    }

    private class GzipBufferCB extends IteratingNestedCallback
    {
        private final ByteBuffer _content;
        private final boolean _last;

        public GzipBufferCB(ByteBuffer content, boolean complete, Callback callback)
        {
            super(callback);
            
            _content = content;
            _last = complete;

            _crc.update(_content.slice());

            Deflater deflater = _deflaterEntry.get();
            deflater.setInput(_content);
            if (_last)
                deflater.finish();
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
            // If we have no deflator
            if (_deflaterEntry == null)
            {
                // then the trailer has been generated and written below.
                // we have finished compressing the entire content, so
                // cleanup and succeed.
                if (_buffer != null)
                {
                    _channel.getByteBufferPool().release(_buffer);
                    _buffer = null;
                }
                return Action.SUCCEEDED;
            }

            // If we have no buffer
            if (_buffer == null)
            {
                // allocate a buffer and add the gzip header
                _buffer = _channel.getByteBufferPool().acquire(_bufferSize, _channel.isUseOutputDirectByteBuffers());
                BufferUtil.fill(_buffer, GZIP_HEADER, 0, GZIP_HEADER.length);
            }
            else
            {
                // otherwise clear the buffer as previous writes will always fully consume.
                BufferUtil.clear(_buffer);
            }

            // If the deflator is not finished, then compress more data
            Deflater deflater = _deflaterEntry.get();
            if (!deflater.finished())
            {
                if (deflater.needsInput() && !_last)
                    return Action.SUCCEEDED;

                int pos = BufferUtil.flipToFill(_buffer);
                deflater.deflate(_buffer, _syncFlush ? Deflater.SYNC_FLUSH : Deflater.NO_FLUSH);
                BufferUtil.flipToFlush(_buffer, pos);
            }

            // If we have finished deflation and there is room for the trailer.
            if (deflater.finished() && BufferUtil.space(_buffer) >= 8)
            {
                // add the trailer and recycle the deflator to flag that we will have had completeSuccess when
                // the write below completes.
                addTrailer();
                _deflaterEntry.release();
                _deflaterEntry = null;
            }

            // write the compressed buffer.
            _interceptor.write(_buffer, _deflaterEntry == null, this);
            return Action.SCHEDULED;
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
                _deflaterEntry != null && _deflaterEntry.get().finished() ? "(finished)" : "");
        }
    }
}
