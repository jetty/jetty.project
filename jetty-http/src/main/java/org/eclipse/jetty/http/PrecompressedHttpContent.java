//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Map;

import org.eclipse.jetty.http.MimeTypes.Type;
import org.eclipse.jetty.util.resource.Resource;

/* ------------------------------------------------------------ */
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
    public int hashCode()
    {
        return _content.hashCode();
    }

    @Override
    public boolean equals(Object obj)
    {
        return _content.equals(obj);
    }

    @Override
    public Resource getResource()
    {
        return _content.getResource();
    }

    @Override
    public HttpField getETag()
    {
        return new HttpField(HttpHeader.ETAG,getETagValue());
    }

    @Override
    public String getETagValue()
    {
        return _content.getResource().getWeakETag(_format._etag);
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
        return _format._contentEncoding;
    }

    @Override
    public String getContentEncodingValue()
    {
        return _format._contentEncoding.getValue();
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
    public void release()
    {
        _content.release();
    }

    @Override
    public ByteBuffer getIndirectBuffer()
    {
        return _precompressedContent.getIndirectBuffer();
    }

    @Override
    public ByteBuffer getDirectBuffer()
    {
        return _precompressedContent.getDirectBuffer();
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
    public InputStream getInputStream() throws IOException
    {
        return _precompressedContent.getInputStream();
    }

    @Override
    public ReadableByteChannel getReadableByteChannel() throws IOException
    {
        return _precompressedContent.getReadableByteChannel();
    }

    @Override
    public String toString()
    {
        return String.format("PrecompressedHttpContent@%x{e=%s,r=%s|%s,lm=%s|%s,ct=%s}",hashCode(),_format._encoding,
                _content.getResource(),_precompressedContent.getResource(),
                _content.getResource().lastModified(),_precompressedContent.getResource().lastModified(),
                getContentType());
    }

    @Override
    public Map<CompressedContentFormat, HttpContent> getPrecompressedContents()
    {
        return null;
    }
}