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

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.jetty.http.MimeTypes.Type;
import org.eclipse.jetty.util.resource.Resource;

public class PrecompressedHttpContent implements HttpContent
{
    private final HttpContent _content;
    private final HttpContent _precompressedContent;
    private final CompressedContentFormat _format;

    public PrecompressedHttpContent(HttpContent content, HttpContent precompressedContent, CompressedContentFormat format)
    {
        _content = content;
        _precompressedContent = precompressedContent;
        _format = format;
        if (_precompressedContent == null || _format == null)
        {
            throw new NullPointerException("Missing compressed content and/or format");
        }
    }

    @Override
    public Path getPath()
    {
        return _content.getPath();
    }

    @Override
    public Resource getResource()
    {
        return _content.getResource();
    }

    @Override
    public HttpField getETag()
    {
        return new HttpField(HttpHeader.ETAG, getETagValue());
    }

    @Override
    public String getETagValue()
    {
        //return _content.getResource().getWeakETag(_format.getEtagSuffix());
        return null;
    }

    @Override
    public HttpField getLastModified()
    {
        return _content.getLastModified();
    }

    @Override
    public String getLastModifiedValue()
    {
        return _content.getLastModifiedValue();
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
            _content.getPath(), _precompressedContent.getPath(),
//            _content.getResource().lastModified(), _precompressedContent.getResource().lastModified(),
            0L, 0L,
            getContentType());
    }

    @Override
    public Map<CompressedContentFormat, HttpContent> getPrecompressedContents()
    {
        return null;
    }

    @Override
    public ByteBuffer getBuffer()
    {
        return _content.getBuffer();
    }

    @Override
    public void release()
    {
        _content.release();
    }
}
