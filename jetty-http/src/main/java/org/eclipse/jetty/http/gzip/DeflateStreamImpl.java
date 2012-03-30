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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/* ------------------------------------------------------------ */
/**
 * Compressed Stream implementation using deflate to compress
 */
public class DeflateStreamImpl extends AbstractCompressedStream implements CompressedStream
{
    public DeflateStreamImpl(HttpServletRequest request, HttpServletResponse response, long contentLength, int bufferSize, int minGzipSize) throws IOException
    {
        super(request,response,contentLength,bufferSize,minGzipSize);
    }

    /**
     * Sets the Content-Encoding header to deflate.
     *
     * @return true, if successful
     */
    @Override
    protected boolean setContentEncoding()
    {
        _response.setHeader("Content-Encoding", CompressionType.DEFLATE.getEncodingHeader());
        return _response.containsHeader("Content-Encoding");
    }

    /**
     * @return a new DeflaterOutputStream backed by _response.getOutputStream()
     */
    @Override
    protected DeflaterOutputStream createStream() throws IOException
    {
        return new DeflaterOutputStream(_response.getOutputStream());
    }
}

