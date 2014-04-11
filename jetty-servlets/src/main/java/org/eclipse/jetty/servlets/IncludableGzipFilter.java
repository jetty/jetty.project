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

package org.eclipse.jetty.servlets;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.gzip.CompressedResponseWrapper;
import org.eclipse.jetty.http.gzip.AbstractCompressedStream;
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

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.servlets.GzipFilter#createWrappedResponse(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, java.lang.String)
     */
    @Override
    protected CompressedResponseWrapper createWrappedResponse(HttpServletRequest request, HttpServletResponse response, final String compressionType)
    {
        CompressedResponseWrapper wrappedResponse = null;
        if (compressionType==null)
        {
            wrappedResponse = new IncludableResponseWrapper(request,response)
            {
                @Override
                protected AbstractCompressedStream newCompressedStream(HttpServletRequest request,HttpServletResponse response) throws IOException
                {
                    return new AbstractCompressedStream(null,request,this,_vary)
                    {
                        @Override
                        protected DeflaterOutputStream createStream() throws IOException
                        {
                            return null;
                        }
                    };
                }
            };
        }
        else if (compressionType.equals(GZIP))
        {
            wrappedResponse = new IncludableResponseWrapper(request,response)
            {
                @Override
                protected AbstractCompressedStream newCompressedStream(HttpServletRequest request,HttpServletResponse response) throws IOException
                {
                    return new AbstractCompressedStream(compressionType,request,this,_vary)
                    {
                        @Override
                        protected DeflaterOutputStream createStream() throws IOException
                        {
                            return new GZIPOutputStream(_response.getOutputStream(),_bufferSize);
                        }
                    };
                }
            };
        }
        else if (compressionType.equals(DEFLATE))
        {
            wrappedResponse = new IncludableResponseWrapper(request,response)
            {
                @Override
                protected AbstractCompressedStream newCompressedStream(HttpServletRequest request,HttpServletResponse response) throws IOException
                {
                    return new AbstractCompressedStream(compressionType,request,this,_vary)
                    {
                        @Override
                        protected DeflaterOutputStream createStream() throws IOException
                        {
                            return new DeflaterOutputStream(_response.getOutputStream(),new Deflater(_deflateCompressionLevel, _deflateNoWrap));
                        }
                    };
                }
            };
        }
        else
        {
            throw new IllegalStateException(compressionType + " not supported");
        }
        configureWrappedResponse(wrappedResponse);
        return wrappedResponse;
    }


    // Extend CompressedResponseWrapper to be able to set headers during include and to create unchecked printwriters
    private abstract class IncludableResponseWrapper extends CompressedResponseWrapper
    {
        public IncludableResponseWrapper(HttpServletRequest request, HttpServletResponse response)
        {
            super(request,response);
        }

        @Override
        public void setHeader(String name,String value)
        {
            super.setHeader(name,value);
            HttpServletResponse response = (HttpServletResponse)getResponse();
            if (!response.containsHeader(name))
                response.setHeader("org.eclipse.jetty.server.include."+name,value);
        }

        @Override
        public void addHeader(String name, String value)
        {
            super.addHeader(name, value);
            HttpServletResponse response = (HttpServletResponse)getResponse();
            if (!response.containsHeader(name))
                setHeader(name,value);
        }
        
        @Override
        protected PrintWriter newWriter(OutputStream out, String encoding) throws UnsupportedEncodingException
        {
            if (_uncheckedPrintWriter)
                return encoding == null?new UncheckedPrintWriter(out):new UncheckedPrintWriter(new OutputStreamWriter(out,encoding));
            return super.newWriter(out,encoding);
        }
    }

}
