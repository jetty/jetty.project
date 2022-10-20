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

package org.eclipse.jetty.http;

import java.time.Instant;
import java.util.Set;

import org.eclipse.jetty.http.MimeTypes.Type;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.resource.Resource;

public class PreCompressedHttpContent implements HttpContent
{
    private final HttpContent _content;
    private final HttpContent _precompressedContent;
    private final CompressedContentFormat _format;
    private final HttpField _etag;

    public PreCompressedHttpContent(HttpContent content, HttpContent precompressedContent, CompressedContentFormat format)
    {
        if (content == null)
            throw new IllegalArgumentException("Null HttpContent");
        if (precompressedContent == null)
            throw new IllegalArgumentException("Null Precompressed HttpContent");
        if (format == null)
            throw new IllegalArgumentException("Null Compressed Content Format");

        _content = content;
        _precompressedContent = precompressedContent;
        _format = format;
        _etag = new HttpField(HttpHeader.ETAG, EtagUtils.rewriteWithSuffix(_content.getETagValue(), _format.getEtagSuffix()));
    }

    @Override
    public Resource getResource()
    {
        return _precompressedContent.getResource();
    }

    @Override
    public HttpField getETag()
    {
        return _etag;
    }

    @Override
    public String getETagValue()
    {
        return getETag().getValue();
    }

    @Override
    public Instant getLastModifiedInstant()
    {
        return _precompressedContent.getLastModifiedInstant();
    }

    @Override
    public HttpField getLastModified()
    {
        return _precompressedContent.getLastModified();
    }

    @Override
    public String getLastModifiedValue()
    {
        return _precompressedContent.getLastModifiedValue();
    }

    @Override
    public HttpField getContentType()
    {
        return _content.getContentType();
    }

    @Override
    public String getContentTypeValue()
    {
        return _content.getContentTypeValue();
    }

    @Override
    public HttpField getContentEncoding()
    {
        return _format.getContentEncoding();
    }

    @Override
    public String getContentEncodingValue()
    {
        return _format.getContentEncoding().getValue();
    }

    @Override
    public String getCharacterEncoding()
    {
        return _content.getCharacterEncoding();
    }

    @Override
    public Type getMimeType()
    {
        return _content.getMimeType();
    }

    @Override
    public HttpField getContentLength()
    {
        return _precompressedContent.getContentLength();
    }

    @Override
    public long getContentLengthValue()
    {
        return _precompressedContent.getContentLengthValue();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{e=%s,r=%s|%s,lm=%s|%s,ct=%s}",
            this.getClass().getSimpleName(), hashCode(),
            _format,
            _content.getResource().lastModified(), _precompressedContent.getResource().lastModified(),
            0L, 0L,
            getContentType());
    }

    @Override
    public RetainableByteBuffer getBuffer()
    {
        return _precompressedContent.getBuffer();
    }

    @Override
    public Set<CompressedContentFormat> getPreCompressedContentFormats()
    {
        return _content.getPreCompressedContentFormats();
    }

    @Override
    public void release()
    {
        _precompressedContent.release();
    }
}
