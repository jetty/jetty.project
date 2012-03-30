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
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/* ------------------------------------------------------------ */
/**
 */
public class DeflateResponseWrapperImpl extends AbstractCompressedResponseWrapper
{
    /**
     * Instantiates a new deflate response wrapper.
     *
     * @param request the request
     * @param response the response
     */
    public DeflateResponseWrapperImpl(HttpServletRequest request, HttpServletResponse response)
    {
        super(request,response);
    }

    
    /* ------------------------------------------------------------ */
    /**
     * @param request the request
     * @param response the response
     * @param contentLength the content length
     * @param bufferSize the buffer size
     * @param minCompressSize the min compress size
     * @return the deflate stream
     * @throws IOException Signals that an I/O exception has occurred.
     */
    @Override
    protected CompressedStream newCompressedStream(HttpServletRequest request,HttpServletResponse response,long contentLength,int bufferSize, int minCompressSize) throws IOException
    {
        return new DeflateStreamImpl(request,response,contentLength,bufferSize,minCompressSize);
    }
}

