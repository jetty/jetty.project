//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

/* ------------------------------------------------------------ */
/** HttpContent.
 * 
 *
 */
public interface HttpContent
{
    Buffer getContentType();
    Buffer getLastModified();
    Buffer getIndirectBuffer();
    Buffer getDirectBuffer();
    Resource getResource();
    long getContentLength();
    InputStream getInputStream() throws IOException;
    void release();
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public class ResourceAsHttpContent implements HttpContent
    {
        private static final Logger LOG = Log.getLogger(ResourceAsHttpContent.class);
        
        final Resource _resource;
        final Buffer _mimeType;
        final int _maxBuffer;

        public ResourceAsHttpContent(final Resource resource, final Buffer mimeType)
        {
            _resource=resource;
            _mimeType=mimeType;
            _maxBuffer=-1;
        }
        
        public ResourceAsHttpContent(final Resource resource, final Buffer mimeType, int maxBuffer)
        {
            _resource=resource;
            _mimeType=mimeType;
            _maxBuffer=maxBuffer;
        }

        /* ------------------------------------------------------------ */
        public Buffer getContentType()
        {
            return _mimeType;
        }

        /* ------------------------------------------------------------ */
        public Buffer getLastModified()
        {
            return null;
        }

        /* ------------------------------------------------------------ */
        public Buffer getDirectBuffer()
        {
            return null;
        }

        /* ------------------------------------------------------------ */
        public Buffer getIndirectBuffer()
        {
            InputStream inputStream = null;
            try
            {
                if (_resource.length() <= 0 || _maxBuffer < _resource.length())
                    return null;
                ByteArrayBuffer buffer = new ByteArrayBuffer((int)_resource.length());
                inputStream = _resource.getInputStream();
                buffer.readFrom(inputStream,(int)_resource.length());
                return buffer;
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
            finally
            {
                if (inputStream != null)
                {
                    try
                    {
                        inputStream.close();
                    }
                    catch (IOException e)
                    {
                        LOG.warn("Couldn't close inputStream. Possible file handle leak",e);
                    }
                }
            }
        }

        /* ------------------------------------------------------------ */
        public long getContentLength()
        {
            return _resource.length();
        }

        /* ------------------------------------------------------------ */
        public InputStream getInputStream() throws IOException
        {
            return _resource.getInputStream();
        }

        /* ------------------------------------------------------------ */
        public Resource getResource()
        {
            return _resource;
        }

        /* ------------------------------------------------------------ */
        public void release()
        {
            _resource.release();
        }
    }
}
