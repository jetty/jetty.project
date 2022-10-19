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
import java.time.Instant;
import java.util.Set;

import org.eclipse.jetty.http.MimeTypes.Type;
import org.eclipse.jetty.util.resource.Resource;

/**
 * HttpContent Wrapper.
 */
public class HttpContentWrapper implements HttpContent
{
    private final HttpContent _delegate;

    public HttpContentWrapper(HttpContent content)
    {
        _delegate = content;
    }

    public HttpContent getWrapped()
    {
        return _delegate;
    }

    @Override
    public HttpField getContentType()
    {
        return _delegate.getContentType();
    }

    @Override
    public String getContentTypeValue()
    {
        return _delegate.getContentTypeValue();
    }

    @Override
    public String getCharacterEncoding()
    {
        return _delegate.getCharacterEncoding();
    }

    @Override
    public Type getMimeType()
    {
        return _delegate.getMimeType();
    }

    @Override
    public HttpField getContentEncoding()
    {
        return _delegate.getContentEncoding();
    }

    @Override
    public String getContentEncodingValue()
    {
        return _delegate.getContentEncodingValue();
    }

    @Override
    public HttpField getContentLength()
    {
        return _delegate.getContentLength();
    }

    @Override
    public long getContentLengthValue()
    {
        return _delegate.getContentLengthValue();
    }

    @Override
    public Instant getLastModifiedInstant()
    {
        return _delegate.getLastModifiedInstant();
    }

    @Override
    public HttpField getLastModified()
    {
        return _delegate.getLastModified();
    }

    @Override
    public String getLastModifiedValue()
    {
        return _delegate.getLastModifiedValue();
    }

    @Override
    public HttpField getETag()
    {
        return _delegate.getETag();
    }

    @Override
    public String getETagValue()
    {
        return _delegate.getETagValue();
    }

    @Override
    public Resource getResource()
    {
        return _delegate.getResource();
    }

    @Override
    public ByteBuffer getBuffer()
    {
        return _delegate.getBuffer();
    }

    @Override
    public Set<CompressedContentFormat> getPreCompressedContentFormats()
    {
        return _delegate.getPreCompressedContentFormats();
    }

    @Override
    public void release()
    {
        _delegate.release();
    }

    @Override
    public String toString()
    {
        return "%s@%x[%s]".formatted(getClass().getSimpleName(), hashCode(), _delegate);
    }
}
