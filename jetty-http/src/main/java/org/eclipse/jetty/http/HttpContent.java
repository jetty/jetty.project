// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

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
    Resource getResource();
    long getContentLength();
    InputStream getInputStream() throws IOException;
    void release();

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public class ResourceAsHttpContent implements HttpContent
    {
        final Resource _resource;
        final String _mimeType;
        final int _maxBuffer;

        public ResourceAsHttpContent(final Resource resource, final String mimeType)
        {
            _resource=resource;
            _mimeType=mimeType;
            _maxBuffer=-1;
        }

        public ResourceAsHttpContent(final Resource resource, final String mimeType, int maxBuffer)
        {
            _resource=resource;
            _mimeType=mimeType;
            _maxBuffer=maxBuffer;
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
            return null;
        }

        /* ------------------------------------------------------------ */
        @Override
        public ByteBuffer getIndirectBuffer()
        {
            try
            {
                if (_resource.length()<=0 || _maxBuffer<_resource.length())
                    return null;
                int length=(int)_resource.length();
                byte[] array = new byte[length];

                int offset=0;
                InputStream in=_resource.getInputStream();

                do
                {
                    int filled=in.read(array,offset,length);
                    if (filled<0)
                        break;
                    length-=filled;
                    offset+=filled;
                }
                while(length>0);

                ByteBuffer buffer = ByteBuffer.wrap(array);
                return buffer;
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
        public Resource getResource()
        {
            return _resource;
        }

        /* ------------------------------------------------------------ */
        @Override
        public void release()
        {
            _resource.release();
        }
    }
}
