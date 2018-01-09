//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.servlets.gzip;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.IncludeExclude;
import org.eclipse.jetty.util.RegexSet;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/**
 * GZIP Handler This handler will gzip the content of a response if:
 * <ul>
 * <li>The handler is mapped to a matching path</li>
 * <li>The response status code is >=200 and <300
 * <li>The content length is unknown or more than the <code>minGzipSize</code> initParameter or the minGzipSize is 0(default)</li>
 * <li>The content-type matches one of the set of mimetypes to be compressed</li>
 * <li>The content-type does NOT match one of the set of mimetypes AND setExcludeMimeTypes is <code>true</code></li>
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

    protected int _bufferSize = 8192;
    protected int _minGzipSize = 256;
    protected String _vary = "Accept-Encoding, User-Agent";
    
    private final IncludeExclude<String> _agentPatterns=new IncludeExclude<>(RegexSet.class);
    private final IncludeExclude<String> _methods = new IncludeExclude<>();
    private final IncludeExclude<String> _paths = new IncludeExclude<String>(PathSpecSet.class);
    private final IncludeExclude<String> _mimeTypes = new IncludeExclude<>();

    /* ------------------------------------------------------------ */
    /**
     * Instantiates a new gzip handler.
     */
    public GzipHandler()
    {
        _methods.include(HttpMethod.GET.asString());
        for (String type:MimeTypes.getKnownMimeTypes())
        {
            if ("image/svg+xml".equals(type))
                _paths.exclude("*.svgz");
            else if (type.startsWith("image/")||
                type.startsWith("audio/")||
                type.startsWith("video/"))
                _mimeTypes.exclude(type);
        }
        _mimeTypes.exclude("application/compress");
        _mimeTypes.exclude("application/zip");
        _mimeTypes.exclude("application/gzip");
        _mimeTypes.exclude("application/bzip2");
        _mimeTypes.exclude("application/x-rar-compressed");
        LOG.debug("{} mime types {}",this,_mimeTypes);
        
        _agentPatterns.exclude(".*MSIE 6.0.*");
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param patterns Regular expressions matching user agents to exclude
     */
    public void addExcludedAgentPatterns(String... patterns)
    {
        _agentPatterns.exclude(patterns);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param methods The methods to exclude in compression
     */
    public void addExcludedMethods(String... methods)
    {
        for (String m : methods)
            _methods.exclude(m);
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the mime types.
     * @param types The mime types to exclude (without charset or other parameters).
     * For backward compatibility the mimetypes may be comma separated strings, but this
     * will not be supported in future versions.
     */
    public void addExcludedMimeTypes(String... types)
    {
        for (String t : types)
            _mimeTypes.exclude(StringUtil.csvSplit(t));
    }

    /* ------------------------------------------------------------ */
    /**
     * Add path to excluded paths list.
     * <p>
     * There are 2 syntaxes supported, Servlet <code>url-pattern</code> based, and
     * Regex based.  This means that the initial characters on the path spec
     * line are very strict, and determine the behavior of the path matching.
     * <ul>
     *  <li>If the spec starts with <code>'^'</code> the spec is assumed to be
     *      a regex based path spec and will match with normal Java regex rules.</li>
     *  <li>If the spec starts with <code>'/'</code> then spec is assumed to be
     *      a Servlet url-pattern rules path spec for either an exact match
     *      or prefix based match.</li>
     *  <li>If the spec starts with <code>'*.'</code> then spec is assumed to be
     *      a Servlet url-pattern rules path spec for a suffix based match.</li>
     *  <li>All other syntaxes are unsupported</li> 
     * </ul>
     * <p>
     * Note: inclusion takes precedence over exclude.
     * 
     * @param pathspecs Path specs (as per servlet spec) to exclude. If a 
     * ServletContext is available, the paths are relative to the context path,
     * otherwise they are absolute.<br>
     * For backward compatibility the pathspecs may be comma separated strings, but this
     * will not be supported in future versions.
     */
    public void addExcludedPaths(String... pathspecs)
    {
        for (String p : pathspecs)
            _paths.exclude(StringUtil.csvSplit(p));
    }

    /* ------------------------------------------------------------ */
    /**
     * @param patterns Regular expressions matching user agents to exclude
     */
    public void addIncludedAgentPatterns(String... patterns)
    {
        _agentPatterns.include(patterns);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param methods The methods to include in compression
     */
    public void addIncludedMethods(String... methods)
    {
        for (String m : methods)
            _methods.include(m);
    }

    /* ------------------------------------------------------------ */
    /**
     * Add included mime types. Inclusion takes precedence over
     * exclusion.
     * @param types The mime types to include (without charset or other parameters)
     * For backward compatibility the mimetypes may be comma separated strings, but this
     * will not be supported in future versions.
     */
    public void addIncludedMimeTypes(String... types)
    {
        for (String t : types)
            _mimeTypes.include(StringUtil.csvSplit(t));
    }

    /* ------------------------------------------------------------ */
    /**
     * Add path specs to include.
     * <p>
     * There are 2 syntaxes supported, Servlet <code>url-pattern</code> based, and
     * Regex based.  This means that the initial characters on the path spec
     * line are very strict, and determine the behavior of the path matching.
     * <ul>
     *  <li>If the spec starts with <code>'^'</code> the spec is assumed to be
     *      a regex based path spec and will match with normal Java regex rules.</li>
     *  <li>If the spec starts with <code>'/'</code> then spec is assumed to be
     *      a Servlet url-pattern rules path spec for either an exact match
     *      or prefix based match.</li>
     *  <li>If the spec starts with <code>'*.'</code> then spec is assumed to be
     *      a Servlet url-pattern rules path spec for a suffix based match.</li>
     *  <li>All other syntaxes are unsupported</li> 
     * </ul>
     * <p>
     * Note: inclusion takes precedence over exclude.
     * 
     * @param pathspecs Path specs (as per servlet spec) to include. If a 
     * ServletContext is available, the paths are relative to the context path,
     * otherwise they are absolute
     */
    public void addIncludedPaths(String... pathspecs)
    {
        for (String p : pathspecs)
            _paths.include(StringUtil.csvSplit(p));
    }
    
    /* ------------------------------------------------------------ */
    public String[] getExcludedAgentPatterns()
    {
        Set<String> excluded=_agentPatterns.getExcluded();
        return excluded.toArray(new String[excluded.size()]);
    }

    /* ------------------------------------------------------------ */
    public String[] getExcludedMethods()
    {
        Set<String> excluded=_methods.getExcluded();
        return excluded.toArray(new String[excluded.size()]);
    }

    /* ------------------------------------------------------------ */
    public String[] getExcludedMimeTypes()
    {
        Set<String> excluded=_mimeTypes.getExcluded();
        return excluded.toArray(new String[excluded.size()]);
    }

    /* ------------------------------------------------------------ */
    public String[] getExcludedPaths()
    {
        Set<String> excluded=_paths.getExcluded();
        return excluded.toArray(new String[excluded.size()]);
    }

    /* ------------------------------------------------------------ */
    public String[] getIncludedAgentPatterns()
    {
        Set<String> includes=_agentPatterns.getIncluded();
        return includes.toArray(new String[includes.size()]);
    }
    
    /* ------------------------------------------------------------ */
    public String[] getIncludedMethods()
    {
        Set<String> includes=_methods.getIncluded();
        return includes.toArray(new String[includes.size()]);
    }

    /* ------------------------------------------------------------ */
    public String[] getIncludedMimeTypes()
    {
        Set<String> includes=_mimeTypes.getIncluded();
        return includes.toArray(new String[includes.size()]);
    }

    /* ------------------------------------------------------------ */
    public String[] getIncludedPaths()
    {
        Set<String> includes=_paths.getIncluded();
        return includes.toArray(new String[includes.size()]);
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the mime types.
     *
     * @return mime types to set
     * @deprecated use {@link #getExcludedMimeTypes()} or {@link #getIncludedMimeTypes()} instead
     */
    @Deprecated
    public Set<String> getMimeTypes()
    {
        throw new UnsupportedOperationException("Use getIncludedMimeTypes or getExcludedMimeTypes instead");
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the mime types.
     *
     * @param mimeTypes
     *            the mime types to set
     * @deprecated use {@link #setExcludedMimeTypes()} or {@link #setIncludedMimeTypes()} instead
     */
    @Deprecated
    public void setMimeTypes(Set<String> mimeTypes)
    {
        throw new UnsupportedOperationException("Use setIncludedMimeTypes or setExcludedMimeTypes instead");
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the mime types.
     *
     * @param mimeTypes
     *            the mime types to set
     * @deprecated use {@link #setExcludedMimeTypes()} or {@link #setIncludedMimeTypes()} instead
     */
    @Deprecated
    public void setMimeTypes(String mimeTypes)
    {
        throw new UnsupportedOperationException("Use setIncludedMimeTypes or setExcludedMimeTypes instead");
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the mime types.
     * @deprecated use {@link #setExcludedMimeTypes()} instead
     */
    @Deprecated
    public void setExcludeMimeTypes(boolean exclude)
    {
        throw new UnsupportedOperationException("Use setExcludedMimeTypes instead");
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the excluded user agents.
     *
     * @return excluded user agents
     */
    public Set<String> getExcluded()
    {
        return _agentPatterns.getExcluded();
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
        _agentPatterns.getExcluded().clear();
        _agentPatterns.getExcluded().addAll(excluded);
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
        _agentPatterns.getExcluded().clear();

        if (excluded != null)
        {
            _agentPatterns.exclude(StringUtil.csvSplit(excluded));
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
    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.handler.HandlerWrapper#handle(java.lang.String, org.eclipse.jetty.server.Request, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if(_handler == null || !isStarted())
        {
            // do nothing
            return;
        }
        
        if(isGzippable(baseRequest, request, response))
        {
            final CompressedResponseWrapper wrappedResponse = newGzipResponseWrapper(request,response);

            boolean exceptional=true;
            try
            {
                _handler.handle(target, baseRequest, request, wrappedResponse);
                exceptional=false;
            }
            finally
            {
                if (request.isAsyncStarted())
                {
                    request.getAsyncContext().addListener(new AsyncListener()
                    {
                        
                        @Override
                        public void onTimeout(AsyncEvent event) throws IOException
                        {
                        }
                        
                        @Override
                        public void onStartAsync(AsyncEvent event) throws IOException
                        {
                        }
                        
                        @Override
                        public void onError(AsyncEvent event) throws IOException
                        {
                        }
                        
                        @Override
                        public void onComplete(AsyncEvent event) throws IOException
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

    private boolean isGzippable(Request baseRequest, HttpServletRequest request, HttpServletResponse response)
    {
        String ae = request.getHeader("accept-encoding");
        if (ae == null || !ae.contains("gzip"))
        {
            // Request not indicated for Gzip
            return false;
        }
        
        if(response.containsHeader("Content-Encoding"))
        {
            // Response is already declared, can't gzip
            LOG.debug("{} excluded as Content-Encoding already declared {}",this,request);
            return false;
        }
        
        if(HttpMethod.HEAD.is(request.getMethod()))
        {
            // HEAD is never Gzip'd
            LOG.debug("{} excluded by method {}",this,request);
            return false;
        }
        
        // Exclude based on Request Method
        if (!_methods.matches(baseRequest.getMethod()))
        {
            LOG.debug("{} excluded by method {}",this,request);
            return false;
        }
        
        // Exclude based on Request Path
        ServletContext context = baseRequest.getServletContext();
        String path = context==null?baseRequest.getRequestURI():URIUtil.addPaths(baseRequest.getServletPath(),baseRequest.getPathInfo());

        if(path != null && !_paths.matches(path))
        {
            LOG.debug("{} excluded by path {}",this,request);
            return false;
        }
        
        
        // Exclude non compressible mime-types known from URI extension. - no Vary because no matter what client, this URI is always excluded
        String mimeType = context==null?null:context.getMimeType(path);
        if (mimeType!=null)
        {
            mimeType = MimeTypes.getContentTypeWithoutCharset(mimeType);
            if (!_mimeTypes.matches(mimeType))
            {
                LOG.debug("{} excluded by path suffix mime type {}",this,request);
                return false;
            }
        }
        
        // Exclude on User Agent
        String ua = request.getHeader("User-Agent");
        if(ua != null && !_agentPatterns.matches(ua))
        {
            LOG.debug("{} excluded by user-agent {}",this,request);
            return false;
        }
        
        return true;
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
