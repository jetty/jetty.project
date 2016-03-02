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
import org.eclipse.jetty.http.MimeTypes.Type;

import org.eclipse.jetty.util.resource.Resource;

/* ------------------------------------------------------------ */
public class GzipHttpContent implements HttpContent
{
    private final HttpContent _content; 
    private final HttpContent _contentGz;
    public final static String ETAG_GZIP="--gzip";
    public final static String ETAG_GZIP_QUOTE="--gzip\"";
    public final static PreEncodedHttpField CONTENT_ENCODING_GZIP=new PreEncodedHttpField(HttpHeader.CONTENT_ENCODING,"gzip");
    
    public static String removeGzipFromETag(String etag)
    {
        if (etag==null)
            return null;
        int i = etag.indexOf(ETAG_GZIP_QUOTE);
        if (i<0)
            return etag;
        return etag.substring(0,i)+'"';
    }
    
    public GzipHttpContent(HttpContent content, HttpContent contentGz)
    {  
        _content=content;
        _contentGz=contentGz;
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
        return _content.getResource().getWeakETag(ETAG_GZIP);
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
        return CONTENT_ENCODING_GZIP;
    }

    @Override
    public String getContentEncodingValue()
    {
        return CONTENT_ENCODING_GZIP.getValue();
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
        return _contentGz.getIndirectBuffer();
    }

    @Override
    public ByteBuffer getDirectBuffer()
    {
        return _contentGz.getDirectBuffer();
    }

    @Override
    public HttpField getContentLength()
    {
        return _contentGz.getContentLength();
    }

    @Override
    public long getContentLengthValue()
    {
        return _contentGz.getContentLengthValue();
    }

    @Override
    public InputStream getInputStream() throws IOException
    {
        return _contentGz.getInputStream();
    }

    @Override
    public ReadableByteChannel getReadableByteChannel() throws IOException
    {
        return _contentGz.getReadableByteChannel();
    }

    @Override
    public String toString()
    {
        return String.format("GzipHttpContent@%x{r=%s|%s,lm=%s|%s,ct=%s}",hashCode(),
                _content.getResource(),_contentGz.getResource(),
                _content.getResource().lastModified(),_contentGz.getResource().lastModified(),
                getContentType());
    }

    @Override
    public HttpContent getGzipContent()
    {
        return null;
    }
}