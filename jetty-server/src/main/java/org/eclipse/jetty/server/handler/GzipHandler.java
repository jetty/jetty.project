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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.gzip.CompressedResponseWrapper;
import org.eclipse.jetty.http.gzip.AbstractCompressedStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/**
 * GZIP Handler This handler will gzip the content of a response if:
 * <ul>
 * <li>The filter is mapped to a matching path</li>
 * <li>The response status code is >=200 and <300
 * <li>The content length is unknown or more than the <code>minGzipSize</code> initParameter or the minGzipSize is 0(default)</li>
 * <li>The content-type is in the comma separated list of mimeTypes set in the <code>mimeTypes</code> initParameter or if no mimeTypes are defined the
 * content-type is not "application/gzip"</li>
 * <li>No content-encoding is specified by the resource</li>
 * </ul>
 * 
 * <p>
 * Compressing the content can greatly improve the network bandwidth usage, but at a cost of memory and CPU cycles. If this handler is used for static content,
 * then use of efficient direct NIO may be prevented, thus use of the gzip mechanism of the <code>org.eclipse.jetty.servlet.DefaultServlet</code> is advised instead.
 * </p>
 */
public class GzipHandler extends HandlerWrapper
{
    private static final Logger LOG = Log.getLogger(GzipHandler.class);

    protected Set<String> _mimeTypes;
    protected Set<String> _excluded;
    protected int _bufferSize = 8192;
    protected int _minGzipSize = 256;
    protected String _vary = "Accept-Encoding, User-Agent";

    /* ------------------------------------------------------------ */
    /**
     * Instantiates a new gzip handler.
     */
    public GzipHandler()
    {
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the mime types.
     * 
     * @return mime types to set
     */
    public Set<String> getMimeTypes()
    {
        return _mimeTypes;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the mime types.
     * 
     * @param mimeTypes
     *            the mime types to set
     */
    public void setMimeTypes(Set<String> mimeTypes)
    {
        _mimeTypes = mimeTypes;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the mime types.
     * 
     * @param mimeTypes
     *            the mime types to set
     */
    public void setMimeTypes(String mimeTypes)
    {
        if (mimeTypes != null)
        {
            _mimeTypes = new HashSet<String>();
            StringTokenizer tok = new StringTokenizer(mimeTypes,",",false);
            while (tok.hasMoreTokens())
            {
                _mimeTypes.add(tok.nextToken());
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the excluded user agents.
     * 
     * @return excluded user agents
     */
    public Set<String> getExcluded()
    {
        return _excluded;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the excluded user agents.
     * 
     * @param excluded
     *            excluded user agents to set
     */
    public void setExcluded(Set<String> excluded)
    {
        _excluded = excluded;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the excluded user agents.
     * 
     * @param excluded
     *            excluded user agents to set
     */
    public void setExcluded(String excluded)
    {
        if (excluded != null)
        {
            _excluded = new HashSet<String>();
            StringTokenizer tok = new StringTokenizer(excluded,",",false);
            while (tok.hasMoreTokens())
                _excluded.add(tok.nextToken());
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The value of the Vary header set if a response can be compressed.
     */
    public String getVary()
    {
        return _vary;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the value of the Vary header sent with responses that could be compressed.  
     * <p>
     * By default it is set to 'Accept-Encoding, User-Agent' since IE6 is excluded by 
     * default from the excludedAgents. If user-agents are not to be excluded, then 
     * this can be set to 'Accept-Encoding'.  Note also that shared caches may cache 
     * many copies of a resource that is varied by User-Agent - one per variation of the 
     * User-Agent, unless the cache does some normalization of the UA string.
     * @param vary The value of the Vary header set if a response can be compressed.
     */
    public void setVary(String vary)
    {
        _vary = vary;
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the buffer size.
     * 
     * @return the buffer size
     */
    public int getBufferSize()
    {
        return _bufferSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the buffer size.
     * 
     * @param bufferSize
     *            buffer size to set
     */
    public void setBufferSize(int bufferSize)
    {
        _bufferSize = bufferSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the minimum reponse size.
     * 
     * @return minimum reponse size
     */
    public int getMinGzipSize()
    {
        return _minGzipSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the minimum reponse size.
     * 
     * @param minGzipSize
     *            minimum reponse size
     */
    public void setMinGzipSize(int minGzipSize)
    {
        _minGzipSize = minGzipSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.handler.HandlerWrapper#handle(java.lang.String, org.eclipse.jetty.server.Request, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (_handler!=null && isStarted())
        {
            String ae = request.getHeader("accept-encoding");
            if (ae != null && ae.indexOf("gzip")>=0 && !response.containsHeader("Content-Encoding")
                    && !HttpMethods.HEAD.equalsIgnoreCase(request.getMethod()))
            {
                if (_excluded!=null)
                {
                    String ua = request.getHeader("User-Agent");
                    if (_excluded.contains(ua))
                    {
                        _handler.handle(target,baseRequest, request, response);
                        return;
                    }
                }

                final CompressedResponseWrapper wrappedResponse = newGzipResponseWrapper(request,response);
                
                boolean exceptional=true;
                try
                {
                    _handler.handle(target, baseRequest, request, wrappedResponse);
                    exceptional=false;
                }
                finally
                {
                    Continuation continuation = ContinuationSupport.getContinuation(request);
                    if (continuation.isSuspended() && continuation.isResponseWrapped())   
                    {
                        continuation.addContinuationListener(new ContinuationListener()
                        {
                            public void onComplete(Continuation continuation)
                            {
                                try
                                {
                                    wrappedResponse.finish();
                                }
                                catch(IOException e)
                                {
                                    LOG.warn(e);
                                }
                            }

                            public void onTimeout(Continuation continuation)
                            {}
                        });
                    }
                    else if (exceptional && !response.isCommitted())
                    {
                        wrappedResponse.resetBuffer();
                        wrappedResponse.noCompression();
                    }
                    else
                        wrappedResponse.finish();
                }
            }
            else
            {
                _handler.handle(target,baseRequest, request, response);
            }
        }
    }

    /**
     * Allows derived implementations to replace ResponseWrapper implementation.
     *
     * @param request the request
     * @param response the response
     * @return the gzip response wrapper
     */
    protected CompressedResponseWrapper newGzipResponseWrapper(HttpServletRequest request, HttpServletResponse response)
    {
        return new CompressedResponseWrapper(request,response)
        {
            {
                super.setMimeTypes(GzipHandler.this._mimeTypes);
                super.setBufferSize(GzipHandler.this._bufferSize);
                super.setMinCompressSize(GzipHandler.this._minGzipSize);
            }
            
            @Override
            protected AbstractCompressedStream newCompressedStream(HttpServletRequest request,HttpServletResponse response) throws IOException
            {
                return new AbstractCompressedStream("gzip",request,this,_vary)
                {
                    @Override
                    protected DeflaterOutputStream createStream() throws IOException
                    {
                        return new GZIPOutputStream(_response.getOutputStream(),_bufferSize);
                    }
                };
            }
            
            @Override
            protected PrintWriter newWriter(OutputStream out,String encoding) throws UnsupportedEncodingException
            {
                return GzipHandler.this.newWriter(out,encoding);
            }
        };
    }
    
    /**
     * Allows derived implementations to replace PrintWriter implementation.
     *
     * @param out the out
     * @param encoding the encoding
     * @return the prints the writer
     * @throws UnsupportedEncodingException
     */
    protected PrintWriter newWriter(OutputStream out,String encoding) throws UnsupportedEncodingException
    {
        return encoding==null?new PrintWriter(out):new PrintWriter(new OutputStreamWriter(out,encoding));
    }
}
