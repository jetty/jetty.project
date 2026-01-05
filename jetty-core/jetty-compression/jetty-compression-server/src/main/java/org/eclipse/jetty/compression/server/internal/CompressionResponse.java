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

package org.eclipse.jetty.compression.server.internal;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.compression.EncoderSink;
import org.eclipse.jetty.compression.server.CompressionConfig;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompressionResponse extends Response.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(CompressionResponse.class);

    private final Compression compression;
    private final CompressionConfig config;
    private final String originalEtag;
    private final AtomicReference<State> state = new AtomicReference<>(State.MIGHT_COMPRESS);
    private EncoderSink encoderSink;

    public CompressionResponse(Request request, Response wrapped, Compression compression, CompressionConfig config, String originalEtag)
    {
        super(request, wrapped);
        this.compression = compression;
        this.config = config;
        this.originalEtag = originalEtag;
    }

    @Override
    public HttpFields.Mutable getHeaders()
    {
        if (originalEtag == null)
            return super.getHeaders();

        // To handle the 304 Not Modified case, we need to ensure that the ETag
        // Now need to re-add the compression etag suffix that was stripped
        // from the request before the handling request.
        return new HttpFields.Mutable.Wrapper(super.getHeaders())
        {
            @Override
            public HttpField onAddField(HttpField field)
            {
                if (getStatus() == HttpStatus.NOT_MODIFIED_304)
                {
                    if (field.getHeader() == HttpHeader.ETAG)
                    {
                        return new HttpField(HttpHeader.ETAG, originalEtag);
                    }
                }

                return field;
            }

            @Override
            public HttpField onReplaceField(HttpField oldField, HttpField newField)
            {
                return onAddField(newField);
            }
        };
    }

    @Override
    public void setStatus(int code)
    {
        if (code == HttpStatus.NOT_MODIFIED_304)
        {
            getHeaders().put(HttpHeader.ETAG, originalEtag);
        }
        super.setStatus(code);
    }

    @Override
    public void write(boolean last, ByteBuffer content, Callback callback)
    {
        HttpFields.Mutable headers = getHeaders();

        switch (state.get())
        {
            case MIGHT_COMPRESS ->
            {
                int status = getStatus();
                if (status > 0 && (
                    HttpStatus.isInformational(status) ||
                    // We don't use HttpStatus.hasNoBody(status) here
                    // because we need to handle 304 Not Modified differently for etags
                    status == HttpStatus.NO_CONTENT_204 ||
                    status == HttpStatus.RESET_CONTENT_205 ||
                    // Redirection, Client, and Server errors are not compressed
                    status >= 300) &&
                    !HttpMethod.HEAD.is(getRequest().getMethod()))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("no compression for status {} {}", status, this);
                    state.compareAndSet(State.MIGHT_COMPRESS, State.NOT_COMPRESSING);
                    super.write(last, content, callback);
                    return;
                }

                if (status == HttpStatus.NOT_MODIFIED_304)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("no compression for status {} {}", status, this);
                    state.compareAndSet(State.MIGHT_COMPRESS, State.NOT_COMPRESSING);
                    headers.computeField(HttpHeader.ETAG, (name, value) -> (value == null || value.isEmpty()) ? null : new HttpField(HttpHeader.ETAG, compression.etag(value.get(0).getValue())));
                    super.write(last, content, callback);
                }

                HttpField contentTypeField = headers.getField(HttpHeader.CONTENT_TYPE);
                if (contentTypeField != null)
                {
                    String mimeType = MimeTypes.getContentTypeWithoutCharset(contentTypeField.getValue());
                    if (!config.isCompressMimeTypeSupported(mimeType))
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("no compression for unsupported content type {} {}", mimeType, this);
                        state.compareAndSet(State.MIGHT_COMPRESS, State.NOT_COMPRESSING);
                        super.write(last, content, callback);
                        return;
                    }
                }

                // Did the application explicitly set the Content-Encoding?
                String contentEncoding = headers.get(HttpHeader.CONTENT_ENCODING);
                if (contentEncoding != null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("no compression for explicit content encoding {} {}", contentEncoding, this);
                    state.compareAndSet(State.MIGHT_COMPRESS, State.NOT_COMPRESSING);
                    super.write(last, content, callback);
                    return;
                }

                // If there is nothing to write, don't compress.
                if (last && BufferUtil.isEmpty(content))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("no compression, nothing to write {}", this);
                    state.compareAndSet(State.MIGHT_COMPRESS, State.NOT_COMPRESSING);
                    super.write(last, content, callback);
                    return;
                }

                long contentLength = headers.getLongField(HttpHeader.CONTENT_LENGTH);
                if (contentLength < 0 && last)
                    contentLength = BufferUtil.length(content);
                if (contentLength >= 0 && contentLength < compression.getMinCompressSize())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("no compression, too few content bytes {} {}", contentLength, this);
                    state.compareAndSet(State.MIGHT_COMPRESS, State.NOT_COMPRESSING);
                    super.write(last, content, callback);
                    return;
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("compressing {} {}", compression.getEncodingName(), this);

                state.compareAndSet(State.MIGHT_COMPRESS, State.COMPRESSING);
                this.encoderSink = compression.newEncoderSink(getWrapped());

                // Adjust the headers.
                headers.put(compression.getContentEncodingField());
                headers.remove(HttpHeader.CONTENT_LENGTH);
                headers.computeField(HttpHeader.ETAG, (name, value) -> (value == null || value.isEmpty()) ? null : new HttpField(HttpHeader.ETAG, compression.etag(value.get(0).getValue())));

                this.write(last, content, callback);
            }
            case COMPRESSING -> encoderSink.write(last, content, callback);
            case NOT_COMPRESSING -> super.write(last, content, callback);
        }
    }

    enum State
    {
        MIGHT_COMPRESS,
        NOT_COMPRESSING,
        COMPRESSING
    }
}
