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

/**
 * 
 */
package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.io.InputStream;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.nio.DirectNIOBuffer;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.io.nio.NIOBuffer;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.ResourceCache;
import org.eclipse.jetty.server.nio.NIOConnector;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;

class NIOResourceCache extends ResourceCache
{
    boolean _useFileMappedBuffer;
    
    /* ------------------------------------------------------------ */
    public NIOResourceCache(MimeTypes mimeTypes)
    {
        super(mimeTypes);
    }

    /* ------------------------------------------------------------ */
    protected void fill(Content content) throws IOException
    {
        Buffer buffer=null;
        Resource resource=content.getResource();
        long length=resource.length();

        if (_useFileMappedBuffer && resource.getFile()!=null) 
        {            
	        buffer = new DirectNIOBuffer(resource.getFile());
        } 
        else 
        {
            InputStream is = resource.getInputStream();
            try
            {
                Connector connector = HttpConnection.getCurrentConnection().getConnector();
		 buffer = ((NIOConnector)connector).getUseDirectBuffers()?
			 (NIOBuffer)new DirectNIOBuffer((int)length):
			 (NIOBuffer)new IndirectNIOBuffer((int)length);
            }
            catch(OutOfMemoryError e)
            {
                Log.warn(e.toString());
                Log.debug(e);
		buffer = new IndirectNIOBuffer((int) length);
            }
            buffer.readFrom(is,(int)length);
            is.close();
        }
        content.setBuffer(buffer);
    }

    public boolean isUseFileMappedBuffer()
    {
        return _useFileMappedBuffer;
    }

    public void setUseFileMappedBuffer(boolean useFileMappedBuffer)
    {
        _useFileMappedBuffer = useFileMappedBuffer;
    }
}
