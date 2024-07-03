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

package org.eclipse.jetty.compression.server;

import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DynamicCompressionResponse extends Response.Wrapper  implements Callback, Invocable
{
    private static final Logger LOG = LoggerFactory.getLogger(DynamicCompressionResponse.class);

    private enum State
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

    private final AtomicReference<State> state = new AtomicReference<>(State.MIGHT_COMPRESS);
    private final Callback callback;
    private final CompressionConfig config;
    private final Compression compression;
    private final Compression.Encoder encoder;
    private final boolean syncFlush;
    private RetainableByteBuffer buffer;
    private boolean last;

    public DynamicCompressionResponse(Compression compression, Request request, Response wrapped, Callback callback, CompressionConfig config)
    {
        super(request, wrapped);
        this.callback = callback;
        this.config = config;
        this.compression = compression;
        ByteBufferPool pool = request.getComponents().getByteBufferPool();
        int outputBufferSize = request.getConnectionMetaData().getHttpConfiguration().getOutputBufferSize();
        this.encoder = compression.newEncoder(pool, outputBufferSize);
        syncFlush = config.isSyncFlush();
    }

    @Override
    public void succeeded()
    {
        try
        {
            // We need to write nothing here to intercept the committing of the
            // response and possibly change headers in case write is never called.
            if (last)
                this.callback.succeeded();
            else
                write(true, null, this.callback);
        }
        finally
        {
            if (getRequest() instanceof Destroyable gzipRequest)
                gzipRequest.destroy();
        }
    }

    @Override
    public void failed(Throwable x)
    {
        try
        {
            this.callback.failed(x);
        }
        finally
        {
            if (getRequest() instanceof Destroyable gzipRequest)
                gzipRequest.destroy();
        }
    }

    @Override
    public InvocationType getInvocationType()
    {
        return this.callback.getInvocationType();
    }

    @Override
    public void write(boolean last, ByteBuffer content, Callback callback)
    {
        this.last = last;
        switch (state.get())
        {
            case MIGHT_COMPRESS -> commit(last, callback, content);
            case NOT_COMPRESSING -> super.write(last, content, callback);
            case COMMITTING -> callback.failed(new WritePendingException());
            case COMPRESSING -> gzip(last, callback, content);
            default ->
            {
                if (BufferUtil.isEmpty(content))
                    callback.succeeded();
                else
                    callback.failed(new IllegalStateException("state=" + state.get()));
            }
        }
    }

    private void gzip(boolean complete, final Callback callback, ByteBuffer content)
    {
        if (content != null || complete)
            new CompressionBufferCB(complete, callback, content).iterate();
        else
            callback.succeeded();
    }

    protected void commit(boolean last, Callback callback, ByteBuffer content)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("commit(last={}, callback={}, content={})", last, callback, BufferUtil.toDetailString(content));

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
                String requestEtags = (String)request.getAttribute(DynamicCompressionHandler.HANDLER_ETAGS);
                String responseEtag = fields.get(HttpHeader.ETAG);
                if (requestEtags != null && responseEtag != null)
                {
                    String responseEtagGzip = compression.etag(responseEtag);
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
            if (!config.isMimeTypeCompressible(baseType))
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
        if (last && BufferUtil.isEmpty(content))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} exclude by nothing to write", this);
            noCompression();
            super.write(true, content, callback);
            return;
        }

        // Are we the thread that commits?
        if (state.compareAndSet(State.MIGHT_COMPRESS, State.COMMITTING))
        {
            long contentLength = fields.getLongField(HttpHeader.CONTENT_LENGTH);
            if (contentLength < 0 && last)
                contentLength = BufferUtil.length(content);

            if (compression.acceptsCompression(request.getHeaders(), contentLength))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} exclude no deflater", this);
                state.set(State.NOT_COMPRESSING);
                super.write(last, content, callback);
                return;
            }

            fields.put(compression.getContentEncodingField());
            encoder.begin();

            // Adjust headers
            fields.remove(HttpHeader.CONTENT_LENGTH);
            String etag = fields.get(HttpHeader.ETAG);
            if (etag != null)
                fields.put(HttpHeader.ETAG, compression.etag(etag));

            if (LOG.isDebugEnabled())
                LOG.debug("{} compressing with {}", this, encoder);
            state.set(State.COMPRESSING);

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

    public void noCompression()
    {
        while (true)
        {
            switch (state.get())
            {
                case NOT_COMPRESSING:
                    return;

                case MIGHT_COMPRESS:
                    if (state.compareAndSet(State.MIGHT_COMPRESS, State.NOT_COMPRESSING))
                        return;
                    break;

                default:
                    throw new IllegalStateException(state.get().toString());
            }
        }
    }

    private class CompressionBufferCB extends IteratingNestedCallback
    {
        private final ByteBuffer _content;
        private final boolean _last;

        public CompressionBufferCB(boolean complete, Callback callback, ByteBuffer content)
        {
            super(callback);
            _content = content;
            _last = complete;

            if (_content != null)
            {
                encoder.setInput(_content);
            }

            if (LOG.isDebugEnabled())
                LOG.debug("CompressionBufferCB(complete={}, callback={}, content={})", complete, callback, BufferUtil.toDetailString(content));
        }

        @Override
        protected void onCompleteFailure(Throwable x)
        {
            encoder.cleanup();
            super.onCompleteFailure(x);
        }

        @Override
        protected Action process() throws Exception
        {
            if (LOG.isDebugEnabled())
                LOG.debug("CompressionBufferCB.process(): _last={}, _buffer={}, _content={}", _last, buffer, BufferUtil.toDetailString(_content));

            State gzstate = state.get();

            // Are we finished?
            if (gzstate == State.FINISHED)
            {
                // then the trailer has been generated and written below.
                // We have finished compressing the entire content, so
                // cleanup and succeed.
                cleanup();
                return Action.SUCCEEDED;
            }

            // If we have no buffer
            if (buffer == null)
            {
                buffer = encoder.initialBuffer();
            }
            else
            {
                // otherwise clear the buffer as previous writes will always fully consume.
                BufferUtil.clearToFill(buffer.getByteBuffer());
            }

            return switch (gzstate)
            {
                case COMPRESSING -> compressing(buffer.getByteBuffer());
                case FINISHING -> finishing(buffer.getByteBuffer());
                default -> throw new IllegalStateException("Unexpected state [" + state.get() + "]");
            };
        }

        private void cleanup()
        {
            encoder.cleanup();

            if (buffer != null)
            {
                buffer.release();
                buffer = null;
            }
        }

        /**
            * This method is called directly from {@link #process()} to perform the compressing of
         * the content this {@code CompressionBufferCB} represents.
         */
        private Action compressing(ByteBuffer outputBuffer)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("compressing() outputBuffer={}", BufferUtil.toDetailString(outputBuffer));

            if (!encoder.finished())
            {
                if (!encoder.needsInput())
                {
                    int len = encoder.encode(outputBuffer);
                    if (len > 0)
                    {
                        BufferUtil.flipToFlush(outputBuffer, 0);
                        write(false, outputBuffer);
                        return Action.SCHEDULED;
                    }
                }
            }

            if (_last)
            {
                state.set(State.FINISHING);
                encoder.finish();
                return finishing(outputBuffer);
            }

            BufferUtil.flipToFlush(outputBuffer, 0);
            if (outputBuffer.hasRemaining())
            {
                write(false, outputBuffer);
                return Action.SCHEDULED;
            }

            // the content held by CompressionBufferCB is fully consumed as input to the Deflater instance, we are done
            if (BufferUtil.isEmpty(_content))
                return Action.SUCCEEDED;

            // No progress made on deflate, but the _content wasn't consumed, we shouldn't be able to reach this.
            throw new AssertionError("No progress on deflate made for " + this);
        }

        /**
            * This method is called by {@link #compressing(ByteBuffer)}, once the last chunk is compressed;
         * or directly from {@link #process()} if an earlier call to this method was unable to complete.
         */
        private Action finishing(ByteBuffer outputBuffer)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("finishing() outputBuffer={}", BufferUtil.toDetailString(outputBuffer));
            if (!encoder.finished())
            {
                int len = encoder.encode(outputBuffer);

                // try to preserve single write if possible (header + compressed content + trailer)
                if (encoder.finished() && outputBuffer.remaining() >= encoder.trailerSize())
                {
                    state.set(State.FINISHED);
                    encoder.addTrailer(outputBuffer);
                    BufferUtil.flipToFlush(outputBuffer, 0);
                    write(true, outputBuffer);
                    return Action.SCHEDULED;
                }

                if (len > 0)
                {
                    BufferUtil.flipToFlush(outputBuffer, 0);
                    write(false, outputBuffer);
                    return Action.SCHEDULED;
                }

                // No progress made on deflate, deflater not finished, we shouldn't be able to reach this.
                throw new AssertionError("No progress on deflate made for " + this);
            }
            else
            {
                state.set(State.FINISHED);
                encoder.addTrailer(outputBuffer);
                BufferUtil.flipToFlush(outputBuffer, 0);
                write(true, outputBuffer);
                return Action.SCHEDULED;
            }
        }

        private void write(boolean last, ByteBuffer outputBuffer)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("write() last={}, outputBuffer={}", last, BufferUtil.toDetailString(outputBuffer));
            DynamicCompressionResponse.super.write(last, outputBuffer, this);
        }

        @Override
        public String toString()
        {
            return String.format("%s[content=%s last=%b buffer=%s %s]",
                super.toString(),
                BufferUtil.toDetailString(_content),
                _last,
                buffer,
                state.get());
        }
    }
}
