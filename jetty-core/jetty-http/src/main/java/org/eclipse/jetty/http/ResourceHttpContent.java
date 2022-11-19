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
import java.time.Instant;
import java.util.Set;

import org.eclipse.jetty.http.MimeTypes.Known;
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
    final Path _path;
    final String _contentType;
    final HttpField _etag;

    public ResourceHttpContent(final Resource resource, final String contentType)
    {
        _resource = resource;
        _path = resource.getPath();
        _contentType = contentType;
        _etag = EtagUtils.createWeakEtagField(resource);
    }

    @Override
    public String getContentTypeValue()
    {
        return _contentType;
    }

    @Override
    public HttpField getContentType()
    {
        return _contentType == null ? null : new HttpField(HttpHeader.CONTENT_TYPE, _contentType);
    }

    @Override
    public HttpField getContentEncoding()
    {
        return null;
    }

    @Override
    public String getContentEncodingValue()
    {
        return null;
    }

    @Override
    public String getCharacterEncoding()
    {
        return _contentType == null ? null : MimeTypes.getCharsetFromContentType(_contentType);
    }

    @Override
    public Known getMimeType()
    {
        return _contentType == null ? null : MimeTypes.CACHE.get(MimeTypes.getContentTypeWithoutCharset(_contentType));
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
    public String getLastModifiedValue()
    {
        Instant lm = _resource.lastModified();
        return DateGenerator.formatDate(lm);
    }

    @Override
    public HttpField getETag()
    {
        return _etag;
    }

    @Override
    public String getETagValue()
    {
        if (_etag == null)
            return null;
        return _etag.getValue();
    }

    @Override
    public HttpField getContentLength()
    {
        long l = getContentLengthValue();
        return l == -1 ? null : new HttpField.LongValueHttpField(HttpHeader.CONTENT_LENGTH, l);
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
    public ByteBuffer getByteBuffer()
    {
        return null;
    }

    @Override
    public Set<CompressedContentFormat> getPreCompressedContentFormats()
    {
        return null;
    }

    @Override
    public void release()
    {
    }
}
