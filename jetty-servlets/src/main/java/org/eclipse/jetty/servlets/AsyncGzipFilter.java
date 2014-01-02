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

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Pattern;
import java.util.zip.Deflater;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.servlets.gzip.GzipFactory;
import org.eclipse.jetty.servlets.gzip.GzipHttpOutput;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/** Async GZIP Filter
 * This filter is a gzip filter using jetty internal mechanism to apply gzip compression
 * to output that is compatible with async IO and does not need to wrap the response nor output stream.
 * The filter will gzip the content of a response if: <ul>
 * <li>The filter is mapped to a matching path</li>
 * <li>accept-encoding header is set to either gzip, deflate or a combination of those</li>
 * <li>The response status code is >=200 and <300
 * <li>The content length is unknown or more than the <code>minGzipSize</code> initParameter or the minGzipSize is 0(default)</li>
 * <li>If a list of mimeTypes is set by the <code>mimeTypes</code> init parameter, then the Content-Type is in the list.</li>
 * <li>If no mimeType list is set, then the content-type is not in the list defined by <code>excludedMimeTypes</code></li>
 * <li>No content-encoding is specified by the resource</li>
 * </ul>
 *
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
 * <dl>
 * <dt>bufferSize</dt>       <dd>The output buffer size. Defaults to 8192. Be careful as values <= 0 will lead to an
 *                            {@link IllegalArgumentException}.
 *                            See: {@link java.util.zip.GZIPOutputStream#GZIPOutputStream(java.io.OutputStream, int)}
 *                            and: {@link java.util.zip.DeflaterOutputStream#DeflaterOutputStream(java.io.OutputStream, Deflater, int)}
 * </dd>
 * <dt>minGzipSize</dt>       <dd>Content will only be compressed if content length is either unknown or greater
 *                            than <code>minGzipSize</code>.
 * </dd>
 * <dt>deflateCompressionLevel</dt>       <dd>The compression level used for deflate compression. (0-9).
 *                            See: {@link java.util.zip.Deflater#Deflater(int, boolean)}
 * </dd>
 * <dt>deflateNoWrap</dt>       <dd>The noWrap setting for deflate compression. Defaults to true. (true/false)
 *                            See: {@link java.util.zip.Deflater#Deflater(int, boolean)}
 * </dd>
 * <dt>methods</dt>       <dd>Comma separated list of HTTP methods to compress. If not set, only GET requests are compressed.
 *  </dd>
 * <dt>mimeTypes</dt>       <dd>Comma separated list of mime types to compress. If it is not set, then the excludedMimeTypes list is used.
 * </dd>
 * <dt>excludedMimeTypes</dt>       <dd>Comma separated list of mime types to never compress. If not set, then the default is the commonly known
 * image, video, audio and compressed types.
 * </dd>

 * <dt>excludedAgents</dt>       <dd>Comma separated list of user agents to exclude from compression. Does a
 *                            {@link String#contains(CharSequence)} to check if the excluded agent occurs
 *                            in the user-agent header. If it does -> no compression
 * </dd>
 * <dt>excludeAgentPatterns</dt>       <dd>Same as excludedAgents, but accepts regex patterns for more complex matching.
 * </dd>
 * <dt>excludePaths</dt>       <dd>Comma separated list of paths to exclude from compression.
 *                            Does a {@link String#startsWith(String)} comparison to check if the path matches.
 *                            If it does match -> no compression. To match subpaths use <code>excludePathPatterns</code>
 *                            instead.
 * </dd>
 * <dt>excludePathPatterns</dt>       <dd>Same as excludePath, but accepts regex patterns for more complex matching.
 * </dd>
 * <dt>vary</dt>       <dd>Set to the value of the Vary header sent with responses that could be compressed.  By default it is 
 *                            set to 'Vary: Accept-Encoding, User-Agent' since IE6 is excluded by default from the excludedAgents. 
 *                            If user-agents are not to be excluded, then this can be set to 'Vary: Accept-Encoding'.  Note also 
 *                            that shared caches may cache copies of a resource that is varied by User-Agent - one per variation of 
 *                            the User-Agent, unless the cache does some normalization of the UA string.
 * </dd>                         
 * <dt>checkGzExists</dt>       <dd>If set to true, the filter check if a static resource with ".gz" appended exists.  If so then
 *                            the normal processing is done so that the default servlet can send  the pre existing gz content.
 *  </dd>
 *  </dl>
 */
public class AsyncGzipFilter extends UserAgentFilter implements GzipFactory
{
    private static final Logger LOG = Log.getLogger(GzipFilter.class);
    public final static String GZIP = "gzip";
    public static final String DEFLATE = "deflate";
    public final static String ETAG = "o.e.j.s.GzipFilter.ETag";
    public final static int DEFAULT_MIN_GZIP_SIZE=256;

    protected ServletContext _context;
    protected final Set<String> _mimeTypes=new HashSet<>();
    protected boolean _excludeMimeTypes;
    protected int _bufferSize=8192;
    protected int _minGzipSize=DEFAULT_MIN_GZIP_SIZE;
    protected int _deflateCompressionLevel=Deflater.DEFAULT_COMPRESSION;
    protected boolean _deflateNoWrap = true;
    protected boolean _checkGzExists = true;
    
    // non-static, as other GzipFilter instances may have different configurations
    protected final ThreadLocal<Deflater> _deflater = new ThreadLocal<Deflater>();

    protected final static ThreadLocal<byte[]> _buffer= new ThreadLocal<byte[]>();

    protected final Set<String> _methods=new HashSet<String>();
    protected Set<String> _excludedAgents;
    protected Set<Pattern> _excludedAgentPatterns;
    protected Set<String> _excludedPaths;
    protected Set<Pattern> _excludedPathPatterns;
    protected HttpField _vary=new HttpGenerator.CachedHttpField(HttpHeader.VARY,HttpHeader.ACCEPT_ENCODING+", "+HttpHeader.USER_AGENT);

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
        LOG.debug("{} bufferSize={}",this,_bufferSize);

        tmp=filterConfig.getInitParameter("minGzipSize");
        if (tmp!=null)
            _minGzipSize=Integer.parseInt(tmp);
        LOG.debug("{} minGzipSize={}",this,_minGzipSize);

        tmp=filterConfig.getInitParameter("deflateCompressionLevel");
        if (tmp!=null)
            _deflateCompressionLevel=Integer.parseInt(tmp);
        LOG.debug("{} deflateCompressionLevel={}",this,_deflateCompressionLevel);

        tmp=filterConfig.getInitParameter("deflateNoWrap");
        if (tmp!=null)
            _deflateNoWrap=Boolean.parseBoolean(tmp);
        LOG.debug("{} deflateNoWrap={}",this,_deflateNoWrap);

        tmp=filterConfig.getInitParameter("checkGzExists");
        if (tmp!=null)
            _checkGzExists=Boolean.parseBoolean(tmp);
        LOG.debug("{} checkGzExists={}",this,_checkGzExists);
        
        tmp=filterConfig.getInitParameter("methods");
        if (tmp!=null)
        {
            StringTokenizer tok = new StringTokenizer(tmp,",",false);
            while (tok.hasMoreTokens())
                _methods.add(tok.nextToken().trim().toUpperCase(Locale.ENGLISH));
        }
        else
            _methods.add(HttpMethod.GET.asString());
        LOG.debug("{} methods={}",this,_methods);
        
        tmp=filterConfig.getInitParameter("mimeTypes");
        if (tmp==null)
        {
            _excludeMimeTypes=true;
            tmp=filterConfig.getInitParameter("excludedMimeTypes");
            if (tmp==null)
            {
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
            else
            {
                StringTokenizer tok = new StringTokenizer(tmp,",",false);
                while (tok.hasMoreTokens())
                    _mimeTypes.add(tok.nextToken().trim());
            }
        }
        else
        {
            StringTokenizer tok = new StringTokenizer(tmp,",",false);
            while (tok.hasMoreTokens())
                _mimeTypes.add(tok.nextToken().trim());
        }
        LOG.debug("{} mimeTypes={}",this,_mimeTypes);
        LOG.debug("{} excludeMimeTypes={}",this,_excludeMimeTypes);
        tmp=filterConfig.getInitParameter("excludedAgents");
        if (tmp!=null)
        {
            _excludedAgents=new HashSet<String>();
            StringTokenizer tok = new StringTokenizer(tmp,",",false);
            while (tok.hasMoreTokens())
               _excludedAgents.add(tok.nextToken().trim());
        }
        LOG.debug("{} excludedAgents={}",this,_excludedAgents);

        tmp=filterConfig.getInitParameter("excludeAgentPatterns");
        if (tmp!=null)
        {
            _excludedAgentPatterns=new HashSet<Pattern>();
            StringTokenizer tok = new StringTokenizer(tmp,",",false);
            while (tok.hasMoreTokens())
                _excludedAgentPatterns.add(Pattern.compile(tok.nextToken().trim()));
        }
        LOG.debug("{} excludedAgentPatterns={}",this,_excludedAgentPatterns);

        tmp=filterConfig.getInitParameter("excludePaths");
        if (tmp!=null)
        {
            _excludedPaths=new HashSet<String>();
            StringTokenizer tok = new StringTokenizer(tmp,",",false);
            while (tok.hasMoreTokens())
                _excludedPaths.add(tok.nextToken().trim());
        }
        LOG.debug("{} excludedPaths={}",this,_excludedPaths);

        tmp=filterConfig.getInitParameter("excludePathPatterns");
        if (tmp!=null)
        {
            _excludedPathPatterns=new HashSet<Pattern>();
            StringTokenizer tok = new StringTokenizer(tmp,",",false);
            while (tok.hasMoreTokens())
                _excludedPathPatterns.add(Pattern.compile(tok.nextToken().trim()));
        }
        LOG.debug("{} excludedPathPatterns={}",this,_excludedPathPatterns);
        
        tmp=filterConfig.getInitParameter("vary");
        if (tmp!=null)
            _vary=new HttpGenerator.CachedHttpField(HttpHeader.VARY,tmp);
        LOG.debug("{} vary={}",this,_vary);
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
        LOG.debug("{} doFilter {}",this,req);
        HttpServletRequest request=(HttpServletRequest)req;
        HttpServletResponse response=(HttpServletResponse)res;

        // If not a supported method or it is an Excluded URI or an excluded UA - no Vary because no matter what client, this URI is always excluded
        String requestURI = request.getRequestURI();
        if (!_methods.contains(request.getMethod()))
        {
            LOG.debug("{} excluded by method {}",this,request);
            super.doFilter(request,response,chain);
            return;
        }
        
        if (isExcludedPath(requestURI))
        {
            LOG.debug("{} excluded by path {}",this,request);
            super.doFilter(request,response,chain);
            return;
        }
        
        // Exclude non compressible mime-types known from URI extension. - no Vary because no matter what client, this URI is always excluded
        if (_mimeTypes.size()>0)
        {
            String mimeType = _context.getMimeType(request.getRequestURI());
            
            if (mimeType!=null && _mimeTypes.contains(mimeType)==_excludeMimeTypes)
            {
                LOG.debug("{} excluded by path suffix {}",this,request);
                // handle normally without setting vary header
                super.doFilter(request,response,chain);
                return;
            }
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
                    super.doFilter(request,response,chain);
                    return;
                }
            }
        }
        
        // Special handling for etags
        String etag = request.getHeader("If-None-Match"); 
        if (etag!=null)
        {
            int dd=etag.indexOf("--");
            if (dd>0)
                request.setAttribute(ETAG,etag.substring(0,dd)+(etag.endsWith("\"")?"\"":""));
        }

        HttpChannel<?> channel = HttpChannel.getCurrentHttpChannel();
        HttpOutput out = channel.getResponse().getHttpOutput();
        if (!(out instanceof GzipHttpOutput))
        {
            if (out.getClass()!=HttpOutput.class)
                throw new IllegalStateException();
            channel.getResponse().setHttpOutput(out = new GzipHttpOutput(channel));
        }
        
        GzipHttpOutput cout=(GzipHttpOutput)out;
        
        try
        {
            cout.mightCompress(this);
            super.doFilter(request,response,chain);
        }
        catch(Throwable e)
        {
            LOG.debug("{} excepted {}",this,request,e);
            if (!response.isCommitted())
            {
                cout.resetBuffer();
                cout.noCompressionIfPossible();
            }
            throw e;
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

    @Override
    public HttpField getVaryField()
    {
        return _vary;
    }

    @Override
    public Deflater getDeflater(Request request, long content_length)
    {
        String ua = getUserAgent(request);
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
        
        String accept = request.getHttpFields().get(HttpHeader.ACCEPT_ENCODING);
        if (accept==null)
        {
            LOG.debug("{} excluded !accept {}",this,request);
            return null;
        }
        
        boolean gzip=false;
        if (GZIP.equals(accept) || accept.startsWith("gzip,"))
            gzip=true;
        else
        {
            List<String> list=HttpFields.qualityList(request.getHttpFields().getValues(HttpHeader.ACCEPT_ENCODING.asString(),","));
            for (String a:list)
            {
                if (GZIP.equalsIgnoreCase(HttpFields.valueParameters(a,null)))
                {
                    gzip=true;
                    break;
                }
            }
        }
        
        if (!gzip)
        {
            LOG.debug("{} excluded not gzip accept {}",this,request);
            return null;
        }
        
        Deflater df = _deflater.get();
        if (df==null)
            df=new Deflater(_deflateCompressionLevel,_deflateNoWrap);        
        else
            _deflater.set(null);
        
        return df;
    }

    @Override
    public void recycle(Deflater deflater)
    {
        deflater.reset();
        if (_deflater.get()==null)
            _deflater.set(deflater);
        
    }
    
    @Override
    public boolean isExcludedMimeType(String mimetype)
    {
        return _mimeTypes.contains(mimetype) == _excludeMimeTypes;
    }

    @Override
    public int getBufferSize()
    {
        return _bufferSize;
    }
    
    
}
