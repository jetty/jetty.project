// ========================================================================
// Copyright (c) Webtide LLC
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

package org.eclipse.jetty.http.gzip;

import java.io.IOException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/* ------------------------------------------------------------ */
/**
 * Compressed Stream implementation using gzip to compress
 */
public class GzipStreamImpl extends AbstractCompressedStream implements CompressedStream
{
    public GzipStreamImpl(HttpServletRequest request, HttpServletResponse response, long contentLength, int bufferSize, int minGzipSize) throws IOException
    {
        super(request,response,contentLength,bufferSize,minGzipSize);
    }

    /**
     * Sets the Content-Encoding header to gzip.
     *
     * @return true, if successful
     */
    @Override
    protected boolean setContentEncoding()
    {
        _response.setHeader("Content-Encoding", CompressionType.GZIP.getEncodingHeader());
        return _response.containsHeader("Content-Encoding");
    }
    
    /**
     * @return a new GZIPOutputStream backed by _response.getOutputStream()
     */
    @Override
    protected DeflaterOutputStream createStream() throws IOException
    {
        return new GZIPOutputStream(_response.getOutputStream(),_bufferSize);
    }
}

