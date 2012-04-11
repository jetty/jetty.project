// ========================================================================
// Copyright (c) 2007-2009 Mort Bay Consulting Pty. Ltd.
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
import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Pattern;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.gzip.CompressedResponseWrapper;
import org.eclipse.jetty.http.gzip.AbstractCompressedStream;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/** GZIP Filter
 * This filter will gzip or deflate the content of a response if: <ul>
 * <li>The filter is mapped to a matching path</li>
 * <li>accept-encoding header is set to either gzip, deflate or a combination of those</li>
 * <li>The response status code is >=200 and <300
 * <li>The content length is unknown or more than the <code>minGzipSize</code> initParameter or the minGzipSize is 0(default)</li>
 * <li>The content-type is in the comma separated list of mimeTypes set in the <code>mimeTypes</code> initParameter or
 * if no mimeTypes are defined the content-type is not "application/gzip"</li>
 * <li>No content-encoding is specified by the resource</li>
 * </ul>
 * 
 * <p>
 * If both gzip and deflate are specified in the accept-encoding header, then gzip will be used.
 * </p>
 * <p>
 * Compressing the content can greatly improve the network bandwidth usage, but at a cost of memory and
 * CPU cycles. If this filter is mapped for static content, then use of efficient direct NIO may be 
 * prevented, thus use of the gzip mechanism of the {@link org.eclipse.jetty.servlet.DefaultServlet} is 
 * advised instead.
 * </p>
 * <p>
 * This filter extends {@link UserAgentFilter} and if the the initParameter <code>excludedAgents</code> 
 * is set to a comma separated list of user agents, then these agents will be excluded from gzip content.
 * </p>
 *
 */
public class GzipFilter extends UserAgentFilter
{
    private static final Logger LOG = Log.getLogger(GzipFilter.class);
    public final static String GZIP="gzip";
    public final static String DEFLATE="deflate";

    protected Set<String> _mimeTypes;
    protected int _bufferSize=8192;
    protected int _minGzipSize=256;
    protected Set<String> _excludedAgents;
    protected Set<Pattern> _excludedAgentPatterns;
    protected Set<String> _excludedPaths;
    protected Set<Pattern> _excludedPathPatterns;

    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.servlets.UserAgentFilter#init(javax.servlet.FilterConfig)
     */
    @Override
    public void init(FilterConfig filterConfig) throws ServletException
    {
        super.init(filterConfig);
        
        String tmp=filterConfig.getInitParameter("bufferSize");
        if (tmp!=null)
            _bufferSize=Integer.parseInt(tmp);

        tmp=filterConfig.getInitParameter("minGzipSize");
        if (tmp!=null)
            _minGzipSize=Integer.parseInt(tmp);
        
        tmp=filterConfig.getInitParameter("mimeTypes");
        if (tmp!=null)
        {
            _mimeTypes=new HashSet<String>();
            StringTokenizer tok = new StringTokenizer(tmp,",",false);
            while (tok.hasMoreTokens())
                _mimeTypes.add(tok.nextToken());
        }
        tmp=filterConfig.getInitParameter("excludedAgents");
        if (tmp!=null)
        {
            _excludedAgents=new HashSet<String>();
            StringTokenizer tok = new StringTokenizer(tmp,",",false);
            while (tok.hasMoreTokens())
               _excludedAgents.add(tok.nextToken());
        }
        
                tmp=filterConfig.getInitParameter("excludeAgentPatterns");
        if (tmp!=null)
        {
            _excludedAgentPatterns=new HashSet<Pattern>();
            StringTokenizer tok = new StringTokenizer(tmp,",",false);
            while (tok.hasMoreTokens())
                _excludedAgentPatterns.add(Pattern.compile(tok.nextToken()));            
        }        
        
        tmp=filterConfig.getInitParameter("excludePaths");
        if (tmp!=null)
        {
            _excludedPaths=new HashSet<String>();
            StringTokenizer tok = new StringTokenizer(tmp,",",false);
            while (tok.hasMoreTokens())
                _excludedPaths.add(tok.nextToken());            
        }
        
        tmp=filterConfig.getInitParameter("excludePathPatterns");
        if (tmp!=null)
        {
            _excludedPathPatterns=new HashSet<Pattern>();
            StringTokenizer tok = new StringTokenizer(tmp,",",false);
            while (tok.hasMoreTokens())
                _excludedPathPatterns.add(Pattern.compile(tok.nextToken()));            
        }       
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.servlets.UserAgentFilter#destroy()
     */
    @Override
    public void destroy()
    {
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.servlets.UserAgentFilter#doFilter(javax.servlet.ServletRequest, javax.servlet.ServletResponse, javax.servlet.FilterChain)
     */
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) 
        throws IOException, ServletException
    {
        HttpServletRequest request=(HttpServletRequest)req;
        HttpServletResponse response=(HttpServletResponse)res;

        String compressionType = selectCompression(request.getHeader("accept-encoding"));
        if (compressionType!=null && !response.containsHeader("Content-Encoding") && !HttpMethods.HEAD.equalsIgnoreCase(request.getMethod()))
        {
            String ua = getUserAgent(request);
            if (isExcludedAgent(ua))
            {
                super.doFilter(request,response,chain);
                return;
            }
            String requestURI = request.getRequestURI();
            if (isExcludedPath(requestURI))
            {
                super.doFilter(request,response,chain);
                return;
            }
            
            CompressedResponseWrapper wrappedResponse = createWrappedResponse(request,response,compressionType);
            
            boolean exceptional=true;
            try
            {
                super.doFilter(request,wrappedResponse,chain);
                exceptional=false;
            }
            finally
            {
                Continuation continuation = ContinuationSupport.getContinuation(request);
                if (continuation.isSuspended() && continuation.isResponseWrapped())   
                {
                    continuation.addContinuationListener(new ContinuationListenerWaitingForWrappedResponseToFinish(wrappedResponse));
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
            super.doFilter(request,response,chain);
        }
    }

    /* ------------------------------------------------------------ */
    private String selectCompression(String encodingHeader)
    {
        // TODO, this could be a little more robust.
        // prefer gzip over deflate
        if (encodingHeader!=null)
        {
            if (encodingHeader.toLowerCase().contains(GZIP))
                return GZIP;
            else if (encodingHeader.toLowerCase().contains(DEFLATE))
                return DEFLATE;
        }
        return null;
    }
    
    protected CompressedResponseWrapper createWrappedResponse(HttpServletRequest request, HttpServletResponse response, final String compressionType)
    {
        CompressedResponseWrapper wrappedResponse = null;
        if (compressionType.equals(GZIP))
        {
            wrappedResponse = new CompressedResponseWrapper(request,response)
            {
                @Override
                protected AbstractCompressedStream newCompressedStream(HttpServletRequest request,HttpServletResponse response,long contentLength,int bufferSize, int minCompressSize) throws IOException
                {
                    return new AbstractCompressedStream(compressionType,request,response,contentLength,bufferSize,minCompressSize)
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
            wrappedResponse = new CompressedResponseWrapper(request,response)
            {
                @Override
                protected AbstractCompressedStream newCompressedStream(HttpServletRequest request,HttpServletResponse response,long contentLength,int bufferSize, int minCompressSize) throws IOException
                {
                    return new AbstractCompressedStream(compressionType,request,response,contentLength,bufferSize,minCompressSize)
                    {
                        @Override
                        protected DeflaterOutputStream createStream() throws IOException
                        {
                            return new DeflaterOutputStream(_response.getOutputStream());
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

    protected void configureWrappedResponse(CompressedResponseWrapper wrappedResponse)
    {
        wrappedResponse.setMimeTypes(_mimeTypes);
        wrappedResponse.setBufferSize(_bufferSize);
        wrappedResponse.setMinCompressSize(_minGzipSize);
    }
     
    private class ContinuationListenerWaitingForWrappedResponseToFinish implements ContinuationListener{
        
        private CompressedResponseWrapper wrappedResponse;

        public ContinuationListenerWaitingForWrappedResponseToFinish(CompressedResponseWrapper wrappedResponse)
        {
            this.wrappedResponse = wrappedResponse;
        }

        public void onComplete(Continuation continuation)
        {
            try
            {
                wrappedResponse.finish();
            }
            catch (IOException e)
            {
                LOG.warn(e);
            }
        }

        public void onTimeout(Continuation continuation)
        {
        }
    }
    
    /**
     * Checks to see if the UserAgent is excluded
     * 
     * @param ua
     *            the user agent
     * @return boolean true if excluded
     */
    private boolean isExcludedAgent(String ua)
    {
        if (ua == null)
            return false;

        if (_excludedAgents != null)
        {
            if (_excludedAgents.contains(ua))
            {
                return true;
            }
        }
        if (_excludedAgentPatterns != null)
        {
            for (Pattern pattern : _excludedAgentPatterns)
            {
                if (pattern.matcher(ua).matches())
                {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Checks to see if the Path is excluded
     * 
     * @param ua
     *            the request uri
     * @return boolean true if excluded
     */
    private boolean isExcludedPath(String requestURI)
    {
        if (requestURI == null)
            return false;
        if (_excludedPaths != null)
        {
            for (String excludedPath : _excludedPaths)
            {
                if (requestURI.startsWith(excludedPath))
                {
                    return true;
                }
            }
        }
        if (_excludedPathPatterns != null)
        {
            for (Pattern pattern : _excludedPathPatterns)
            {
                if (pattern.matcher(requestURI).matches())
                {
                    return true;
                }
            }
        }
        return false;
    }
}
