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

package org.eclipse.jetty.http.content;

import java.time.Instant;
import java.util.Set;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.DateGenerator;
import org.eclipse.jetty.http.EtagUtils;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.MimeTypes.Type;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.IOResources;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.resource.Resource;

/**
 * HttpContent created from a {@link Resource}.
 * <p>The HttpContent is used to server static content that is not
 * cached. So fields and values are only generated as need be an not
 * kept for reuse</p>
 */
public class ResourceHttpContent implements HttpContent
{
    final Resource _resource;
    final HttpField _contentType;
    final HttpField _etag;
    final ByteBufferPool.Sized _sizedBufferPool;

    public ResourceHttpContent(Resource resource, String contentType, ByteBufferPool.Sized sizedByteBufferPool)
    {
        _resource = resource;
        _contentType = contentType == null ? null : new HttpField(HttpHeader.CONTENT_TYPE, contentType);
        _etag = EtagUtils.createWeakEtagField(resource);
        _sizedBufferPool = sizedByteBufferPool;
    }

    @Override
    public HttpField getContentType()
    {
        return _contentType;
    }

    @Override
    public HttpField getContentEncoding()
    {
        return null;
    }

    @Override
    public String getCharacterEncoding()
    {
        return _contentType == null ? null : MimeTypes.getCharsetFromContentType(_contentType.getValue());
    }

    @Override
    public Type getMimeType()
    {
        return _contentType == null ? null : MimeTypes.CACHE.get(MimeTypes.getContentTypeWithoutCharset(_contentType.getValue()));
    }

    @Override
    public Instant getLastModifiedInstant()
    {
        return _resource.lastModified();
    }

    @Override
    public HttpField getLastModified()
    {
        Instant lm = _resource.lastModified();
        return new HttpField(HttpHeader.LAST_MODIFIED, DateGenerator.formatDate(lm));
    }

    @Override
    public HttpField getETag()
    {
        return _etag;
    }

    @Override
    public HttpField getContentLength()
    {
        long l = getContentLengthValue();
        return l == -1L ? null : new HttpField.LongValueHttpField(HttpHeader.CONTENT_LENGTH, l);
    }

    @Override
    public long getContentLengthValue()
    {
        return _resource.length();
    }

    @Override
    public Resource getResource()
    {
        return _resource;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{r=%s,ct=%s}", this.getClass().getSimpleName(), hashCode(), _resource, _contentType);
    }

    @Override
    public void writeTo(Content.Sink sink, long offset, long length, Callback callback)
    {
        IOResources.copy(_resource, sink, _sizedBufferPool, _sizedBufferPool.getSize(), _sizedBufferPool.isDirect(), offset, length, callback);
    }

    @Override
    public Set<CompressedContentFormat> getPreCompressedContentFormats()
    {
        return null;
    }
}
