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

package org.eclipse.jetty.servlets;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;



/* ------------------------------------------------------------ */
/** Includable GZip Filter.
 * This extension to the {@link GzipFilter} that uses Jetty features to allow
 * headers to be set during calls to 
 * {@link javax.servlet.RequestDispatcher#include(javax.servlet.ServletRequest, javax.servlet.ServletResponse)}.
 * This allows the gzip filter to function correct during includes and to make a decision to gzip or not
 * at the time the buffer fills and on the basis of all response headers.
 * 
 * 
 *
 */
public class IncludableGzipFilter extends GzipFilter
{

    protected GZIPResponseWrapper newGZIPResponseWrapper(HttpServletRequest request, HttpServletResponse response)
    {
        return new IncludableResponseWrapper(request,response);
    }

    public class IncludableResponseWrapper extends GzipFilter.GZIPResponseWrapper
    {
        public IncludableResponseWrapper(HttpServletRequest request, HttpServletResponse response)
        {
            super(request,response);
        }
        
        protected GzipStream newGzipStream(HttpServletRequest request,HttpServletResponse response,long contentLength,int bufferSize, int minGzipSize) throws IOException
        {
            return new IncludableGzipStream(request,response,contentLength,bufferSize,minGzipSize);
        }
    }
    
    public class IncludableGzipStream extends GzipFilter.GzipStream
    {
        public IncludableGzipStream(HttpServletRequest request, HttpServletResponse response, long contentLength, int bufferSize, int minGzipSize)
                throws IOException
        {
            super(request,response,contentLength,bufferSize,minGzipSize);
        }

        protected boolean setContentEncodingGzip()
        {
            if (_request.getAttribute("javax.servlet.include.request_uri")!=null)
                _response.setHeader("org.eclipse.jetty.server.include.Content-Encoding", "gzip");
            else
                _response.setHeader("Content-Encoding", "gzip");
                
            return _response.containsHeader("Content-Encoding");
        }
        
    }
    
}
