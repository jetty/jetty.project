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
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;

import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.gzip.CompressedResponseWrapper;
import org.eclipse.jetty.http.gzip.CompressedStream;
import org.eclipse.jetty.http.gzip.CompressionType;
import org.eclipse.jetty.http.gzip.DeflateStreamImpl;
import org.eclipse.jetty.http.gzip.GzipResponseWrapperImpl;
import org.eclipse.jetty.http.gzip.GzipStreamImpl;
import org.eclipse.jetty.io.UncheckedPrintWriter;

/* ------------------------------------------------------------ */
/** Includable GZip Filter.
 * This extension to the {@link GzipFilter} that uses Jetty features to allow
 * headers to be set during calls to 
 * {@link javax.servlet.RequestDispatcher#include(javax.servlet.ServletRequest, javax.servlet.ServletResponse)}.
 * This allows the gzip filter to function correct during includes and to make a decision to gzip or not
 * at the time the buffer fills and on the basis of all response headers.
 * 
 * If the init parameter "uncheckedPrintWriter" is set to "true", then the PrintWriter used by
 * the wrapped getWriter will be {@link UncheckedPrintWriter}.
 *
 */
public class IncludableGzipFilter extends GzipFilter
{
    boolean _uncheckedPrintWriter=false;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException
    {
        super.init(filterConfig);
        
        String tmp=filterConfig.getInitParameter("uncheckedPrintWriter");
        if (tmp!=null)
            _uncheckedPrintWriter=Boolean.valueOf(tmp).booleanValue();
    }

    @Override
    protected CompressedResponseWrapper createWrappedResponse(HttpServletRequest request, HttpServletResponse response, CompressionType compressionType)
    {
        return new IncludableResponseWrapper(request,response);
    }

    public class IncludableResponseWrapper extends GzipResponseWrapperImpl
    {
        public IncludableResponseWrapper(HttpServletRequest request, HttpServletResponse response)
        {
            super(request,response);

            super.setMimeTypes(IncludableGzipFilter.this._mimeTypes);
            super.setBufferSize(IncludableGzipFilter.this._bufferSize);
            super.setMinCompressSize(IncludableGzipFilter.this._minGzipSize);
        }

        @Override
        protected CompressedStream newCompressedStream(HttpServletRequest request, HttpServletResponse response, long contentLength, int bufferSize,
                int minGzipSize) throws IOException
        {
            String encodingHeader = request.getHeader("accept-encoding");
            CompressionType compressionType = CompressionType.getByEncodingHeader(encodingHeader);
            if (compressionType.equals(CompressionType.GZIP))
            {
                return new IncludableGzipStream(request,response,contentLength,bufferSize,minGzipSize);
            }
            else if (compressionType.equals(CompressionType.DEFLATE))
            {
                return new IncludableDeflateStream(request,response,contentLength,bufferSize,minGzipSize);
            }
            else
            {
                throw new IllegalStateException(compressionType.name() + " not supported.");
            }
        }

        @Override
        protected PrintWriter newWriter(OutputStream out, String encoding) throws UnsupportedEncodingException
        {
            if (_uncheckedPrintWriter)
                return encoding == null?new UncheckedPrintWriter(out):new UncheckedPrintWriter(new OutputStreamWriter(out,encoding));
            return super.newWriter(out,encoding);
        }
    }

    public class IncludableGzipStream extends GzipStreamImpl
    {
        public IncludableGzipStream(HttpServletRequest request, HttpServletResponse response, long contentLength, int bufferSize, int minGzipSize)
                throws IOException
        {
            super(request,response,contentLength,bufferSize,minGzipSize);
        }

        @Override
        protected boolean setContentEncoding()
        {
            if (_request.getAttribute("javax.servlet.include.request_uri") != null)
            {
                _response.setHeader("org.eclipse.jetty.server.include.Content-Encoding","gzip");
            }
            else
            {
                _response.setHeader("Content-Encoding","gzip");
            }

            return _response.containsHeader("Content-Encoding");
        }
    }

    public class IncludableDeflateStream extends DeflateStreamImpl
    {
        public IncludableDeflateStream(HttpServletRequest request, HttpServletResponse response, long contentLength, int bufferSize, int minGzipSize)
                throws IOException
        {
            super(request,response,contentLength,bufferSize,minGzipSize);
        }

        @Override
        protected boolean setContentEncoding()
        {
            if (_request.getAttribute("javax.servlet.include.request_uri") != null)
            {
                _response.setHeader("org.eclipse.jetty.server.include.Content-Encoding","deflate");
            }
            else
            {
                _response.setHeader("Content-Encoding","deflate");
            }

            return _response.containsHeader("Content-Encoding");
        }
    }

}
