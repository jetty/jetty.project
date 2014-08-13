//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.resource.Resource;

/* ------------------------------------------------------------ */
/** HttpContent.
 * 
 *
 */
public interface HttpContent
{
    String getContentType();
    String getLastModified();
    ByteBuffer getIndirectBuffer();
    ByteBuffer getDirectBuffer();
    String getETag();
    Resource getResource();
    long getContentLength();
    InputStream getInputStream() throws IOException;
    ReadableByteChannel getReadableByteChannel() throws IOException;
    void release();

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public class ResourceAsHttpContent implements HttpContent
    {
        final Resource _resource;
        final String _mimeType;
        final int _maxBuffer;
        final String _etag;

        /* ------------------------------------------------------------ */
        public ResourceAsHttpContent(final Resource resource, final String mimeType)
        {
            this(resource,mimeType,-1,false);
        }

        /* ------------------------------------------------------------ */
        public ResourceAsHttpContent(final Resource resource, final String mimeType, int maxBuffer)
        {
            this(resource,mimeType,maxBuffer,false);
        }

        /* ------------------------------------------------------------ */
        public ResourceAsHttpContent(final Resource resource, final String mimeType, boolean etag)
        {
            this(resource,mimeType,-1,etag);
        }

        /* ------------------------------------------------------------ */
        public ResourceAsHttpContent(final Resource resource, final String mimeType, int maxBuffer, boolean etag)
        {
            _resource=resource;
            _mimeType=mimeType;
            _maxBuffer=maxBuffer;
            _etag=etag?resource.getWeakETag():null;
        }

        /* ------------------------------------------------------------ */
        @Override
        public String getContentType()
        {
            return _mimeType;
        }

        /* ------------------------------------------------------------ */
        @Override
        public String getLastModified()
        {
            return null;
        }

        /* ------------------------------------------------------------ */
        @Override
        public ByteBuffer getDirectBuffer()
        {
            if (_resource.length()<=0 || _maxBuffer<_resource.length())
                return null;
            try
            {
                return BufferUtil.toBuffer(_resource,true);
            }
            catch(IOException e)
            {
                throw new RuntimeException(e);
            }
        }
        
        /* ------------------------------------------------------------ */
        @Override
        public String getETag()
        {
            return _etag;
        }

        /* ------------------------------------------------------------ */
        @Override
        public ByteBuffer getIndirectBuffer()
        {
            if (_resource.length()<=0 || _maxBuffer<_resource.length())
                return null;
            try
            {
                return BufferUtil.toBuffer(_resource,false);
            }
            catch(IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        /* ------------------------------------------------------------ */
        @Override
        public long getContentLength()
        {
            return _resource.length();
        }

        /* ------------------------------------------------------------ */
        @Override
        public InputStream getInputStream() throws IOException
        {
            return _resource.getInputStream();
        }
        
        /* ------------------------------------------------------------ */
        @Override
        public ReadableByteChannel getReadableByteChannel() throws IOException
        {
            return _resource.getReadableByteChannel();
        }

        /* ------------------------------------------------------------ */
        @Override
        public Resource getResource()
        {
            return _resource;
        }

        /* ------------------------------------------------------------ */
        @Override
        public void release()
        {
            _resource.close();
        }
        
        @Override
        public String toString()
        {
            return String.format("%s@%x{r=%s}",this.getClass().getSimpleName(),hashCode(),_resource);
        }
    }
}
