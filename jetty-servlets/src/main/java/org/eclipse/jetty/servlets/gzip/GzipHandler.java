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

package org.eclipse.jetty.servlets.gzip;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.servlets.GzipFilter;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
public class GzipHandler extends HandlerWrapper implements GzipFactory
{
    private static final Logger LOG = Log.getLogger(GzipHandler.class);

    public final static String GZIP = "gzip";
    public final static String DEFLATE = "deflate";
    public final static String ETAG_GZIP="--gzip";
    public final static String ETAG = "o.e.j.s.GzipFilter.ETag";
    public final static int DEFAULT_MIN_GZIP_SIZE=16;

    protected final Set<String> _mimeTypes=new HashSet<>();
    protected boolean _excludeMimeTypes;
    protected int _bufferSize=32*1024;
    protected int _minGzipSize=DEFAULT_MIN_GZIP_SIZE;
    protected int _deflateCompressionLevel=Deflater.DEFAULT_COMPRESSION;
    protected boolean _checkGzExists = true;
    
    // non-static, as other GzipFilter instances may have different configurations
    protected final ThreadLocal<Deflater> _deflater = new ThreadLocal<Deflater>();

    protected final static ThreadLocal<byte[]> _buffer= new ThreadLocal<byte[]>();

    protected final MimeTypes _knownMimeTypes= new MimeTypes();
    protected final Set<String> _methods=new HashSet<>();
    protected final Set<String> _excludedAgents=new HashSet<>();
    protected final Set<Pattern> _excludedAgentPatterns=new HashSet<>();
    protected final Set<String> _excludedPaths=new HashSet<>();
    protected final Set<Pattern> _excludedPathPatterns=new HashSet<>();
    protected HttpField _vary=new PreEncodedHttpField(HttpHeader.VARY,HttpHeader.ACCEPT_ENCODING+", "+HttpHeader.USER_AGENT);


   
    /* ------------------------------------------------------------ */
    /**
     * Instantiates a new gzip handler.
     */
    public GzipHandler()
    {
        _methods.add(HttpMethod.GET.asString());
        _excludeMimeTypes=true;
        for (String type:MimeTypes.getKnownMimeTypes())
        {
            if (type.startsWith("image/")||
                type.startsWith("audio/")||
                type.startsWith("video/"))
                _mimeTypes.add(type);
            _mimeTypes.add("application/compress");
            _mimeTypes.add("application/zip");
            _mimeTypes.add("application/gzip");
        }
    }

    @Override
    public int getBufferSize()
    {
        return _bufferSize;
    }


    @Override
    public Deflater getDeflater(Request request, long content_length)
    {
        String ua = request.getHttpFields().get(HttpHeader.USER_AGENT);
        if (ua!=null && isExcludedAgent(ua))
        {
            LOG.debug("{} excluded user agent {}",this,request);
            return null;
        }
        
        if (content_length>=0 && content_length<_minGzipSize)
        {
            LOG.debug("{} excluded minGzipSize {}",this,request);
            return null;
        }

        // If not HTTP/2, then we must check the accept encoding header
        if (request.getHttpVersion()!=HttpVersion.HTTP_2)
        {
            HttpField accept = request.getHttpFields().getField(HttpHeader.ACCEPT_ENCODING);

            if (accept==null)
            {
                LOG.debug("{} excluded !accept {}",this,request);
                return null;
            }
            boolean gzip = accept.contains("gzip");

            if (!gzip)
            {
                LOG.debug("{} excluded not gzip accept {}",this,request);
                return null;
            }
        }
        
        Deflater df = _deflater.get();
        if (df==null)
            df=new Deflater(_deflateCompressionLevel,true);        
        else
            _deflater.set(null);
        
        return df;
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
    @Override
    public HttpField getVaryField()
    {
        return _vary;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.handler.HandlerWrapper#handle(java.lang.String, org.eclipse.jetty.server.Request, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        LOG.debug("{} doFilter {}",this,request);

        // If not a supported method or it is an Excluded URI or an excluded UA - no Vary because no matter what client, this URI is always excluded
        String requestURI = request.getRequestURI();
        if (!_methods.contains(request.getMethod()))
        {
            LOG.debug("{} excluded by method {}",this,request);
            _handler.handle(target,baseRequest, request, response);
            return;
        }
        
        if (isExcludedPath(requestURI))
        {
            LOG.debug("{} excluded by path {}",this,request);
            _handler.handle(target,baseRequest, request, response);
            return;
        }

        // Exclude non compressible mime-types known from URI extension. - no Vary because no matter what client, this URI is always excluded
        if (_mimeTypes.size()>0 && _excludeMimeTypes)
        {
            ServletContext context = request.getServletContext();
 
            String mimeType = context==null?_knownMimeTypes.getMimeByExtension(request.getRequestURI()):context.getMimeType(request.getRequestURI());

            if (mimeType!=null)
            {
                mimeType = MimeTypes.getContentTypeWithoutCharset(mimeType);
                if (_mimeTypes.contains(mimeType))
                {
                    LOG.debug("{} excluded by path suffix {}",this,request);
                    // handle normally without setting vary header
                    _handler.handle(target,baseRequest, request, response);
                    return;
                }
            }
        }

        //If the Content-Encoding is already set, then we won't compress
        if (response.getHeader("Content-Encoding") != null)
        {
            _handler.handle(target,baseRequest, request, response);
            return;
        }
        
        if (_checkGzExists && request.getServletContext()!=null)
        {
            String path=request.getServletContext().getRealPath(URIUtil.addPaths(request.getServletPath(),request.getPathInfo()));
            if (path!=null)
            {
                File gz=new File(path+".gz");
                if (gz.exists())
                {
                    LOG.debug("{} gzip exists {}",this,request);
                    // allow default servlet to handle
                    _handler.handle(target,baseRequest, request, response);
                    return;
                }
            }
        }
        
        // Special handling for etags
        String etag = request.getHeader("If-None-Match"); 
        if (etag!=null)
        {
            if (etag.contains(ETAG_GZIP))
                request.setAttribute(ETAG,etag.replace(ETAG_GZIP,""));
        }

        HttpChannel channel = HttpChannel.getCurrentHttpChannel();
        HttpOutput out = channel.getResponse().getHttpOutput();
        // TODO recycle the GzipHttpOutputFilter
        out.setFilter(new GzipHttpOutputInterceptor(this,channel,out.getFilter()));

        _handler.handle(target,baseRequest, request, response);
        
    }

    public int getDeflateCompressionLevel()
    {
        return _deflateCompressionLevel;
    }


    public void setDeflateCompressionLevel(int deflateCompressionLevel)
    {
        _deflateCompressionLevel = deflateCompressionLevel;
    }

    public boolean getCheckGzExists()
    {
        return _checkGzExists;
    }

    public void setCheckGzExists(boolean checkGzExists)
    {
        _checkGzExists = checkGzExists;
    }

    public String[] getMethods()
    {
        return _methods.toArray(new String[_methods.size()]);
    }
    
    public void setMethods(String[] methods)
    {
        _methods.clear();
        for (String m : methods)
            _methods.add(m);
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

    @Override
    public boolean isExcludedMimeType(String mimetype)
    {
        return _mimeTypes.contains(mimetype) == _excludeMimeTypes;
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

    @Override
    public void recycle(Deflater deflater)
    {
        deflater.reset();
        if (_deflater.get()==null)
            _deflater.set(deflater);
        
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
     * Set the mime types.
     */
    public void setExcludeMimeTypes(boolean exclude)
    {
        _excludeMimeTypes=exclude;
    }

    public String[] getMimeTypes()
    {
        return _mimeTypes.toArray(new String[_mimeTypes.size()]);
    }
    
    public void setMimeTypes(String[] types)
    {
        _mimeTypes.clear();
        _mimeTypes.addAll(Arrays.asList(types));
    }
    
    public boolean isExcludeMimeTypes()
    {
        return _excludeMimeTypes;
    }

    public String[] getExcludedPaths()
    {
        return _excludedPaths.toArray(new String[_excludedPaths.size()]);
    }
    
    public void setExcludedPaths(String[] paths)
    {
        _excludedPaths.clear();
        _excludedPaths.addAll(Arrays.asList(paths));
    }

    public String[] getExcludedPathPatterns()
    {
        Pattern[] ps =  _excludedPathPatterns.toArray(new Pattern[_excludedPathPatterns.size()]);
        String[] s = new String[ps.length];
        
        int i=0;
        for (Pattern p: ps)
            s[i++]=p.toString();
        return s;
    }
    
    public void setExcludedPathPatterns(String[] patterns)
    {
        _excludedPathPatterns.clear();
        for (String s : patterns)
            _excludedPathPatterns.add(Pattern.compile(s));
    }

    public String[] getExcludedAgents()
    {
        return _excludedAgents.toArray(new String[_excludedAgents.size()]);
    }
    
    public void setExcludedAgents(String[] paths)
    {
        _excludedAgents.clear();
        _excludedAgents.addAll(Arrays.asList(paths));
    }

    public String[] getExcludedAgentPatterns()
    {
        Pattern[] ps =  _excludedAgentPatterns.toArray(new Pattern[_excludedAgentPatterns.size()]);
        String[] s = new String[ps.length];
        
        int i=0;
        for (Pattern p: ps)
            s[i++]=p.toString();
        return s;
    }
    
    public void setExcludedAgentPatterns(String[] patterns)
    {
        _excludedAgentPatterns.clear();
        for (String s : patterns)
            _excludedAgentPatterns.add(Pattern.compile(s));
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
            _excludeMimeTypes=false;
            _mimeTypes.clear();
            StringTokenizer tok = new StringTokenizer(mimeTypes,",",false);
            while (tok.hasMoreTokens())
            {
                _mimeTypes.add(tok.nextToken());
            }
        }
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
        _vary=new PreEncodedHttpField(HttpHeader.VARY,vary);
    }



}
