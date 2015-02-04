//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.handler.gzip;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.Deflater;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/**
 * A Handler that can dynamically GZIP compress responses.   Unlike 
 * previous and 3rd party GzipFilters, this mechanism works with asynchronously
 * generated responses and does not need to wrap the response or it's output
 * stream.  Instead it uses the efficient {@link HttpOutput.Interceptor} mechanism.
 * <p>
 * The handler can be applied to the entire server (a gzip.mod is included in
 * the distribution) or it may be applied to individual contexts.
 * </p>
 */
public class GzipHandler extends HandlerWrapper implements GzipFactory
{
    private static final Logger LOG = Log.getLogger(GzipHandler.class);

    public final static String GZIP = "gzip";
    public final static String DEFLATE = "deflate";
    public final static String ETAG_GZIP="--gzip";
    public final static String ETAG = "o.e.j.s.Gzip.ETag";
    public final static int DEFAULT_MIN_GZIP_SIZE=16;

    private int _minGzipSize=DEFAULT_MIN_GZIP_SIZE;
    private int _compressionLevel=Deflater.DEFAULT_COMPRESSION;
    private boolean _checkGzExists = true;
    
    // non-static, as other GzipHandler instances may have different configurations
    private final ThreadLocal<Deflater> _deflater = new ThreadLocal<Deflater>();

    private final Set<String> _includedMethods=new HashSet<>();
    private final Set<Pattern> _excludedAgentPatterns=new HashSet<>();
    private final PathMap<Boolean> _excludedPaths=new PathMap<>();
    private final PathMap<Boolean> _includedPaths=new PathMap<>();
    private final Set<String> _excludedMimeTypes=new HashSet<>();
    private final Set<String> _includedMimeTypes=new HashSet<>();
    private HttpField _vary;
    
    private final Set<String> _uaCache = new ConcurrentHashSet<>();
    private int _uaCacheSize = 1024;

    /* ------------------------------------------------------------ */
    /**
     * Instantiates a new gzip handler.
     * The excluded Mime Types are initialized to common known 
     * images, audio, video and other already compressed types.
     * The included methods is initialized to GET.
     * The excluded agent patterns are set to exclude MSIE 6.0
     */
    public GzipHandler()
    {
        _includedMethods.add(HttpMethod.GET.asString());
        for (String type:MimeTypes.getKnownMimeTypes())
        {
            if (type.startsWith("image/")||
                type.startsWith("audio/")||
                type.startsWith("video/"))
                _excludedMimeTypes.add(type);
        }
        _excludedMimeTypes.add("application/compress");
        _excludedMimeTypes.add("application/zip");
        _excludedMimeTypes.add("application/gzip");
        _excludedMimeTypes.add("application/bzip2");
        _excludedMimeTypes.add("application/x-rar-compressed");
        LOG.debug("{} excluding mimes {}",this,_excludedMimeTypes);
        
        _excludedAgentPatterns.add(Pattern.compile(".*MSIE 6.0.*"));
    }

    /* ------------------------------------------------------------ */
    /**
     * @param patterns Regular expressions matching user agents to exclude
     */
    public void addExcludedAgentPatterns(String... patterns)
    {
        for (String s : patterns)
            _excludedAgentPatterns.add(Pattern.compile(s));
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the mime types.
     * @param types The mime types to exclude (without charset or other parameters)
     */
    public void addExcludedMimeTypes(String... types)
    {
        _excludedMimeTypes.addAll(Arrays.asList(types));
    }

    /* ------------------------------------------------------------ */
    /**
     * @param pathspecs Path specs (as per servlet spec) to exclude. If a 
     * ServletContext is available, the paths are relative to the context path,
     * otherwise they are absolute
     */
    public void addExcludedPaths(String... pathspecs)
    {
        for (String ps : pathspecs)
            _excludedPaths.put(ps,Boolean.TRUE);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param methods The methods to include in compression
     */
    public void addIncludedMethods(String... methods)
    {
        for (String m : methods)
            _includedMethods.add(m);
    }

    /* ------------------------------------------------------------ */
    /**
     * Add included mime types. Inclusion takes precedence over
     * exclusion.
     * @param types The mime types to include (without charset or other parameters)
     */
    public void addIncludedMimeTypes(String... types)
    {
        _includedMimeTypes.addAll(Arrays.asList(types));
    }

    /* ------------------------------------------------------------ */
    /**
     * Add path specs to include. Inclusion takes precedence over exclusion.
     * @param pathspecs Path specs (as per servlet spec) to include. If a 
     * ServletContext is available, the paths are relative to the context path,
     * otherwise they are absolute
     */
    public void addIncludedPaths(String... pathspecs)
    {
        for (String ps : pathspecs)
            _includedPaths.put(ps,Boolean.TRUE);
    }
    
    /* ------------------------------------------------------------ */
    public boolean getCheckGzExists()
    {
        return _checkGzExists;
    }

    /* ------------------------------------------------------------ */
    public int getCompressionLevel()
    {
        return _compressionLevel;
    }

    /* ------------------------------------------------------------ */
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
            df=new Deflater(_compressionLevel,true);        
        else
            _deflater.set(null);
        
        return df;
    }
    
    /* ------------------------------------------------------------ */
    public String[] getExcludedAgentPatterns()
    {
        Pattern[] ps =  _excludedAgentPatterns.toArray(new Pattern[_excludedAgentPatterns.size()]);
        String[] s = new String[ps.length];
        
        int i=0;
        for (Pattern p: ps)
            s[i++]=p.toString();
        return s;
    }
    
    /* ------------------------------------------------------------ */
    public String[] getExcludedMimeTypes()
    {
        return _excludedMimeTypes.toArray(new String[_excludedMimeTypes.size()]);
    }

    /* ------------------------------------------------------------ */
    public String[] getExcludedPaths()
    {
        String[] ps =  _excludedPaths.keySet().toArray(new String[_excludedPaths.size()]);
        return ps;
    }

    /* ------------------------------------------------------------ */
    public String[] getIncludedMimeTypes()
    {
        return _includedMimeTypes.toArray(new String[_includedMimeTypes.size()]);
    }

    /* ------------------------------------------------------------ */
    public String[] getIncludedPaths()
    {
        String[] ps =  _includedPaths.keySet().toArray(new String[_includedPaths.size()]);
        return ps;
    }

    /* ------------------------------------------------------------ */
    public String[] getMethods()
    {
        return _includedMethods.toArray(new String[_includedMethods.size()]);
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
    protected void doStart() throws Exception
    {
        _vary=(_excludedAgentPatterns.size()>0)?GzipHttpOutputInterceptor.VARY_ACCEPT_ENCODING_USER_AGENT:GzipHttpOutputInterceptor.VARY_ACCEPT_ENCODING;
        super.doStart();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.handler.HandlerWrapper#handle(java.lang.String, org.eclipse.jetty.server.Request, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        ServletContext context = baseRequest.getServletContext();
        String path = context==null?baseRequest.getRequestURI():URIUtil.addPaths(baseRequest.getServletPath(),baseRequest.getPathInfo());
        LOG.debug("{} handle {} in {}",this,baseRequest,context);
        
        HttpOutput out = baseRequest.getResponse().getHttpOutput();   
        // Are we already being gzipped?
        HttpOutput.Interceptor interceptor = out.getInterceptor();
        while (interceptor!=null)
        {
            if (interceptor instanceof GzipHttpOutputInterceptor)
            {
                LOG.debug("{} already intercepting {}",this,request);
                _handler.handle(target,baseRequest, request, response);
                return;
            }
            interceptor=interceptor.getNextInterceptor();
        }
        
        // If not a supported method - no Vary because no matter what client, this URI is always excluded
        if (!_includedMethods.contains(baseRequest.getMethod()))
        {
            LOG.debug("{} excluded by method {}",this,request);
            _handler.handle(target,baseRequest, request, response);
            return;
        }
        
        // If not a supported URI- no Vary because no matter what client, this URI is always excluded
        // Use pathInfo because this is be
        if (!isPathGzipable(path))
        {
            LOG.debug("{} excluded by path {}",this,request);
            _handler.handle(target,baseRequest, request, response);
            return;
        }

        // Exclude non compressible mime-types known from URI extension. - no Vary because no matter what client, this URI is always excluded
        String mimeType = context==null?null:context.getMimeType(path);
        if (mimeType!=null)
        {
            mimeType = MimeTypes.getContentTypeWithoutCharset(mimeType);
            if (!isMimeTypeGzipable(mimeType))
            {
                LOG.debug("{} excluded by path suffix mime type {}",this,request);
                // handle normally without setting vary header
                _handler.handle(target,baseRequest, request, response);
                return;
            }
        }
        
        if (_checkGzExists && context!=null)
        {
            String realpath=request.getServletContext().getRealPath(path);
            if (realpath!=null)
            {
                File gz=new File(realpath+".gz");
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

        // install interceptor and handle
        out.setInterceptor(new GzipHttpOutputInterceptor(this,_vary,baseRequest.getHttpChannel(),out.getInterceptor()));
        _handler.handle(target,baseRequest, request, response);
        
    }

    /* ------------------------------------------------------------ */
    /**
     * Checks to see if the userAgent is excluded
     *
     * @param ua the user agent
     * @return boolean true if excluded
     */
    protected boolean isExcludedAgent(String ua)
    {
        if (ua == null)
            return false;
        
        if (_excludedAgentPatterns != null)
        {
            
            if (_uaCache.contains(ua))
                return true;
            
            for (Pattern pattern : _excludedAgentPatterns)
            {
                if (pattern.matcher(ua).matches())
                {
                    if (_uaCache.size()>_uaCacheSize)
                        _uaCache.clear();
                    _uaCache.add(ua);
                    return true;
                }
            }
        }

        return false;
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isMimeTypeGzipable(String mimetype)
    {
        if (_includedMimeTypes.size()>0 && _includedMimeTypes.contains(mimetype))
            return true;
        return !_excludedMimeTypes.contains(mimetype);
    }

    /* ------------------------------------------------------------ */
    /**
     * Checks to see if the path is included or not excluded 
     *
     * @param requestURI
     *            the request uri
     * @return boolean true if gzipable
     */
    protected boolean isPathGzipable(String requestURI)
    {
        if (requestURI == null)
            return true;
        
        if (_includedPaths.size()>0 && _includedPaths.containsMatch(requestURI))
            return true;

        if (_excludedPaths.size()>0 && _excludedPaths.containsMatch(requestURI))
            return false;
        
        return true;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void recycle(Deflater deflater)
    {
        deflater.reset();
        if (_deflater.get()==null)
            _deflater.set(deflater);
        
    }

    /* ------------------------------------------------------------ */
    /**
     * @param checkGzExists If true, check if a static gz file exists for
     * the resource that the DefaultServlet may serve as precompressed.
     */
    public void setCheckGzExists(boolean checkGzExists)
    {
        _checkGzExists = checkGzExists;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param compressionLevel  The compression level to use to initialize {@link Deflater#setLevel(int)}
     */
    public void setCompressionLevel(int compressionLevel)
    {
        _compressionLevel = compressionLevel;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param patterns Regular expressions matching user agents to exclude
     */
    public void setExcludedAgentPatterns(String... patterns)
    {
        _excludedAgentPatterns.clear();
        addExcludedAgentPatterns(patterns);
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the mime types.
     * @param types The mime types to exclude (without charset or other parameters)
     */
    public void setExcludedMimeTypes(String... types)
    {
        _excludedMimeTypes.clear();
        addExcludedMimeTypes(types);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param pathspecs Path specs (as per servlet spec) to exclude. If a 
     * ServletContext is available, the paths are relative to the context path,
     * otherwise they are absolute.
     */
    public void setExcludedPaths(String... pathspecs)
    {
        _excludedPaths.clear();
        addExcludedPaths(pathspecs);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param methods The methods to include in compression
     */
    public void setIncludedMethods(String... methods)
    {
        _includedMethods.clear();
        addIncludedMethods(methods);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Set included mime types. Inclusion takes precedence over
     * exclusion.
     * @param types The mime types to include (without charset or other parameters)
     */
    public void setIncludedMimeTypes(String... types)
    {
        _includedMimeTypes.clear();
        addIncludedMimeTypes(types);
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the path specs to include. Inclusion takes precedence over exclusion.
     * @param pathspecs Path specs (as per servlet spec) to include. If a 
     * ServletContext is available, the paths are relative to the context path,
     * otherwise they are absolute
     */
    public void setIncludedPaths(String... pathspecs)
    {
        _includedPaths.clear();
        addIncludedPaths(pathspecs);
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the minimum response size to trigger dynamic compresssion
     *
     * @param minGzipSize minimum response size in bytes
     */
    public void setMinGzipSize(int minGzipSize)
    {
        _minGzipSize = minGzipSize;
    }
    

}
