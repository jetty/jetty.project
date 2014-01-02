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
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.ServletResponseWrapper;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.gzip.CompressedResponseWrapper;
import org.eclipse.jetty.http.gzip.AbstractCompressedStream;
import org.eclipse.jetty.util.StringUtil;
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
 * <p>Init Parameters:</p>
 * <PRE>
 * bufferSize                 The output buffer size. Defaults to 8192. Be careful as values <= 0 will lead to an 
 *                            {@link IllegalArgumentException}. 
 *                            See: {@link java.util.zip.GZIPOutputStream#GZIPOutputStream(java.io.OutputStream, int)}
 *                            and: {@link java.util.zip.DeflaterOutputStream#DeflaterOutputStream(java.io.OutputStream, Deflater, int)}
 *                      
 * minGzipSize                Content will only be compressed if content length is either unknown or greater
 *                            than <code>minGzipSize</code>.
 *                      
 * deflateCompressionLevel    The compression level used for deflate compression. (0-9).
 *                            See: {@link java.util.zip.Deflater#Deflater(int, boolean)}
 *                            
 * deflateNoWrap              The noWrap setting for deflate compression. Defaults to true. (true/false)
 *                            See: {@link java.util.zip.Deflater#Deflater(int, boolean)}
 *
 * methods                    Comma separated list of HTTP methods to compress. If not set, only GET requests are compressed.
 * 
 * mimeTypes                  Comma separated list of mime types to compress. See description above.
 * 
 * excludedAgents             Comma separated list of user agents to exclude from compression. Does a 
 *                            {@link String#contains(CharSequence)} to check if the excluded agent occurs
 *                            in the user-agent header. If it does -> no compression
 *                            
 * excludeAgentPatterns       Same as excludedAgents, but accepts regex patterns for more complex matching.
 * 
 * excludePaths               Comma separated list of paths to exclude from compression. 
 *                            Does a {@link String#startsWith(String)} comparison to check if the path matches.
 *                            If it does match -> no compression. To match subpaths use <code>excludePathPatterns</code>
 *                            instead.
 * 
 * excludePathPatterns        Same as excludePath, but accepts regex patterns for more complex matching.
 * 
 * vary                       Set to the value of the Vary header sent with responses that could be compressed.  By default it is 
 *                            set to 'Vary: Accept-Encoding, User-Agent' since IE6 is excluded by default from the excludedAgents. 
 *                            If user-agents are not to be excluded, then this can be set to 'Vary: Accept-Encoding'.  Note also 
 *                            that shared caches may cache copies of a resource that is varied by User-Agent - one per variation of 
 *                            the User-Agent, unless the cache does some normalization of the UA string.
 * </PRE>
 */
public class GzipFilter extends UserAgentFilter
{
    private static final Logger LOG = Log.getLogger(GzipFilter.class);
    public final static String GZIP="gzip";
    public final static String ETAG_GZIP="--gzip\"";
    public final static String DEFLATE="deflate";
    public final static String ETAG_DEFLATE="--deflate\"";
    public final static String ETAG="o.e.j.s.GzipFilter.ETag";

    protected ServletContext _context;
    protected Set<String> _mimeTypes;
    protected int _bufferSize=8192;
    protected int _minGzipSize=256;
    protected int _deflateCompressionLevel=Deflater.DEFAULT_COMPRESSION;
    protected boolean _deflateNoWrap = true;

    protected final Set<String> _methods=new HashSet<String>();
    protected Set<String> _excludedAgents;
    protected Set<Pattern> _excludedAgentPatterns;
    protected Set<String> _excludedPaths;
    protected Set<Pattern> _excludedPathPatterns;
    protected String _vary="Accept-Encoding, User-Agent";
    
    private static final int STATE_SEPARATOR = 0;
    private static final int STATE_Q = 1;
    private static final int STATE_QVALUE = 2;
    private static final int STATE_DEFAULT = 3;

    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.servlets.UserAgentFilter#init(javax.servlet.FilterConfig)
     */
    @Override
    public void init(FilterConfig filterConfig) throws ServletException
    {
        super.init(filterConfig);
        
        _context=filterConfig.getServletContext();
        
        String tmp=filterConfig.getInitParameter("bufferSize");
        if (tmp!=null)
            _bufferSize=Integer.parseInt(tmp);

        tmp=filterConfig.getInitParameter("minGzipSize");
        if (tmp!=null)
            _minGzipSize=Integer.parseInt(tmp);
        
        tmp=filterConfig.getInitParameter("deflateCompressionLevel");
        if (tmp!=null)
            _deflateCompressionLevel=Integer.parseInt(tmp);
        
        tmp=filterConfig.getInitParameter("deflateNoWrap");
        if (tmp!=null)
            _deflateNoWrap=Boolean.parseBoolean(tmp);
        
        tmp=filterConfig.getInitParameter("methods");
        if (tmp!=null)
        {
            StringTokenizer tok = new StringTokenizer(tmp,",",false);
            while (tok.hasMoreTokens())
                _methods.add(tok.nextToken().trim().toUpperCase());
        }
        else
            _methods.add(HttpMethods.GET);
        
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
        
        tmp=filterConfig.getInitParameter("vary");
        if (tmp!=null)
            _vary=tmp;
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

        // If not a supported method or it is an Excluded URI - no Vary because no matter what client, this URI is always excluded
        String requestURI = request.getRequestURI();
        if (!_methods.contains(request.getMethod()) || isExcludedPath(requestURI))
        {
            super.doFilter(request,response,chain);
            return;
        }
        
        // Exclude non compressible mime-types known from URI extension. - no Vary because no matter what client, this URI is always excluded
        if (_mimeTypes!=null && _mimeTypes.size()>0)
        {
            String mimeType = _context.getMimeType(request.getRequestURI());
            
            if (mimeType!=null && !_mimeTypes.contains(mimeType))
            {
                // handle normally without setting vary header
                super.doFilter(request,response,chain);
                return;
            }
        }
        
        // Excluded User-Agents
        String ua = getUserAgent(request);
        boolean ua_excluded=ua!=null&&isExcludedAgent(ua);
        
        // Acceptable compression type
        String compressionType = ua_excluded?null:selectCompression(request.getHeader("accept-encoding"));
        
        // Special handling for etags
        String etag = request.getHeader("If-None-Match"); 
        if (etag!=null)
        {
            int dd=etag.indexOf("--");
            if (dd>0)
                request.setAttribute(ETAG,etag.substring(0,dd)+(etag.endsWith("\"")?"\"":""));
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

    /* ------------------------------------------------------------ */
    private String selectCompression(String encodingHeader)
    {
        // TODO, this could be a little more robust.
        // prefer gzip over deflate
        String compression = null;
        if (encodingHeader!=null)
        {
            
            String[] encodings = getEncodings(encodingHeader);
            if (encodings != null)
            {
                for (int i=0; i< encodings.length; i++)
                {
                    if (encodings[i].toLowerCase(Locale.ENGLISH).contains(GZIP))
                    {
                        if (isEncodingAcceptable(encodings[i]))
                        {
                            compression = GZIP;
                            break; //prefer Gzip over deflate
                        }
                    }

                    if (encodings[i].toLowerCase(Locale.ENGLISH).contains(DEFLATE))
                    {
                        if (isEncodingAcceptable(encodings[i]))
                        {
                            compression = DEFLATE; //Keep checking in case gzip is acceptable
                        }
                    }
                }
            }
        }
        return compression;
    }
    
    
    private String[] getEncodings (String encodingHeader)
    {
        if (encodingHeader == null)
            return null;
        return encodingHeader.split(",");
    }
    
    private boolean isEncodingAcceptable(String encoding)
    {    
        int state = STATE_DEFAULT;
        int qvalueIdx = -1;
        for (int i=0;i<encoding.length();i++)
        {
            char c = encoding.charAt(i);
            switch (state)
            {
                case STATE_DEFAULT:
                {
                    if (';' == c)
                        state = STATE_SEPARATOR;
                    break;
                }
                case STATE_SEPARATOR:
                {
                    if ('q' == c || 'Q' == c)
                        state = STATE_Q;
                    break;
                }
                case STATE_Q:
                {
                    if ('=' == c)
                        state = STATE_QVALUE;
                    break;
                }
                case STATE_QVALUE:
                {
                    if (qvalueIdx < 0 && '0' == c || '1' == c)
                        qvalueIdx = i;
                    break;
                }
            }
        }
        
        if (qvalueIdx < 0)
            return true;
               
        if ("0".equals(encoding.substring(qvalueIdx).trim()))
            return false;
        return true;
    }
    
    
    protected CompressedResponseWrapper createWrappedResponse(HttpServletRequest request, HttpServletResponse response, final String compressionType)
    {
        CompressedResponseWrapper wrappedResponse = null;
        if (compressionType==null)
        {
            wrappedResponse = new CompressedResponseWrapper(request,response)
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
            wrappedResponse = new CompressedResponseWrapper(request,response)
            {
                @Override
                protected AbstractCompressedStream newCompressedStream(HttpServletRequest request,HttpServletResponse response) throws IOException
                {
                    return new AbstractCompressedStream(compressionType,request,this,_vary)
                    {
                        @Override
                        protected DeflaterOutputStream createStream() throws IOException
                        {
                            return new GZIPOutputStream(_response.getOutputStream(),_bufferSize)
                            {
                                /**
                                 * Work around a bug in the jvm GzipOutputStream whereby it is not
                                 * thread safe when thread A calls finish, but thread B is writing
                                 * @see java.util.zip.GZIPOutputStream#finish()
                                 */
                                @Override
                                public synchronized void finish() throws IOException
                                {
                                    super.finish();
                                }
                                
                                /**
                                 * Work around a bug in the jvm GzipOutputStream whereby it is not
                                 * thread safe when thread A calls close(), but thread B is writing
                                 * @see java.util.zip.GZIPOutputStream#close()
                                 */
                                @Override
                                public synchronized void close() throws IOException
                                {
                                    super.close();
                                }           
                            };
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
                protected AbstractCompressedStream newCompressedStream(HttpServletRequest request,HttpServletResponse response) throws IOException
                {
                    return new AbstractCompressedStream(compressionType,request,this,_vary)
                    {
                        @Override
                        protected DeflaterOutputStream createStream() throws IOException
                        {
                            return new DeflaterOutputStream(_response.getOutputStream(),new Deflater(_deflateCompressionLevel,_deflateNoWrap));
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
     
    private class ContinuationListenerWaitingForWrappedResponseToFinish implements ContinuationListener
    {    
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
     * Checks to see if the userAgent is excluded
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
     * Checks to see if the path is excluded
     * 
     * @param requestURI
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
