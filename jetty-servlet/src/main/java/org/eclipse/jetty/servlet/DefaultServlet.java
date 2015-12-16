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

package org.eclipse.jetty.servlet;

import static org.eclipse.jetty.http.GzipHttpContent.ETAG_GZIP_QUOTE;
import static org.eclipse.jetty.http.GzipHttpContent.removeGzipFromETag;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.StringTokenizer;

import javax.servlet.AsyncContext;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.DateParser;
import org.eclipse.jetty.http.GzipHttpContent;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.PathMap.MappedEntry;
import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.ResourceHttpContent;
import org.eclipse.jetty.io.WriterOutputStream;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.server.InclusiveByteRange;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.ResourceCache;
import org.eclipse.jetty.server.ResourceContentFactory;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.MultiPartOutputStream;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.util.resource.ResourceFactory;


/** 
 * The default servlet.
 * <p>
 * This servlet, normally mapped to /, provides the handling for static
 * content, OPTION and TRACE methods for the context.
 * The following initParameters are supported, these can be set either
 * on the servlet itself or as ServletContext initParameters with a prefix
 * of org.eclipse.jetty.servlet.Default. :
 * <pre>
 *  acceptRanges      If true, range requests and responses are
 *                    supported
 *
 *  dirAllowed        If true, directory listings are returned if no
 *                    welcome file is found. Else 403 Forbidden.
 *
 *  welcomeServlets   If true, attempt to dispatch to welcome files
 *                    that are servlets, but only after no matching static
 *                    resources could be found. If false, then a welcome
 *                    file must exist on disk. If "exact", then exact
 *                    servlet matches are supported without an existing file.
 *                    Default is true.
 *
 *                    This must be false if you want directory listings,
 *                    but have index.jsp in your welcome file list.
 *
 *  redirectWelcome   If true, welcome files are redirected rather than
 *                    forwarded to.
 *
 *  gzip              If set to true, then static content will be served as
 *                    gzip content encoded if a matching resource is
 *                    found ending with ".gz"
 *
 *  resourceBase      Set to replace the context resource base
 *
 *  resourceCache     If set, this is a context attribute name, which the servlet
 *                    will use to look for a shared ResourceCache instance.
 *
 *  relativeResourceBase
 *                    Set with a pathname relative to the base of the
 *                    servlet context root. Useful for only serving static content out
 *                    of only specific subdirectories.
 *
 *  pathInfoOnly      If true, only the path info will be applied to the resourceBase
 *
 *  stylesheet	      Set with the location of an optional stylesheet that will be used
 *                    to decorate the directory listing html.
 *
 *  etags             If True, weak etags will be generated and handled.
 *
 *  maxCacheSize      The maximum total size of the cache or 0 for no cache.
 *  maxCachedFileSize The maximum size of a file to cache
 *  maxCachedFiles    The maximum number of files to cache
 *
 *  useFileMappedBuffer
 *                    If set to true, it will use mapped file buffer to serve static content
 *                    when using NIO connector. Setting this value to false means that
 *                    a direct buffer will be used instead of a mapped file buffer.
 *                    This is set to false by default by this class, but may be overridden
 *                    by eg webdefault.xml 
 *
 *  cacheControl      If set, all static content will have this value set as the cache-control
 *                    header.
 *                    
 * otherGzipFileExtensions
 *                    Other file extensions that signify that a file is gzip compressed. Eg ".svgz"
 *
 *
 * </pre>
 *
 */
public class DefaultServlet extends HttpServlet implements ResourceFactory
{
    private static final Logger LOG = Log.getLogger(DefaultServlet.class);

    private static final long serialVersionUID = 4930458713846881193L;
    
    private static final PreEncodedHttpField ACCEPT_RANGES = new PreEncodedHttpField(HttpHeader.ACCEPT_RANGES, "bytes");
    
    private ServletContext _servletContext;
    private ContextHandler _contextHandler;

    private boolean _acceptRanges=true;
    private boolean _dirAllowed=true;
    private boolean _welcomeServlets=false;
    private boolean _welcomeExactServlets=false;
    private boolean _redirectWelcome=false;
    private boolean _gzip=false;
    private boolean _pathInfoOnly=false;
    private boolean _etags=false;

    private Resource _resourceBase;
    private ResourceCache _cache;
    private HttpContent.Factory _contentFactory;

    private MimeTypes _mimeTypes;
    private String[] _welcomes;
    private Resource _stylesheet;
    private boolean _useFileMappedBuffer=false;
    private HttpField _cacheControl;
    private String _relativeResourceBase;
    private ServletHandler _servletHandler;
    private ServletHolder _defaultHolder;
    private List<String> _gzipEquivalentFileExtensions;

    /* ------------------------------------------------------------ */
    @Override
    public void init()
    throws UnavailableException
    {
        _servletContext=getServletContext();
        _contextHandler = initContextHandler(_servletContext);

        _mimeTypes = _contextHandler.getMimeTypes();

        _welcomes = _contextHandler.getWelcomeFiles();
        if (_welcomes==null)
            _welcomes=new String[] {"index.html","index.jsp"};

        _acceptRanges=getInitBoolean("acceptRanges",_acceptRanges);
        _dirAllowed=getInitBoolean("dirAllowed",_dirAllowed);
        _redirectWelcome=getInitBoolean("redirectWelcome",_redirectWelcome);
        _gzip=getInitBoolean("gzip",_gzip);
        _pathInfoOnly=getInitBoolean("pathInfoOnly",_pathInfoOnly);

        if ("exact".equals(getInitParameter("welcomeServlets")))
        {
            _welcomeExactServlets=true;
            _welcomeServlets=false;
        }
        else
            _welcomeServlets=getInitBoolean("welcomeServlets", _welcomeServlets);

        _useFileMappedBuffer=getInitBoolean("useFileMappedBuffer",_useFileMappedBuffer);

        _relativeResourceBase = getInitParameter("relativeResourceBase");

        String rb=getInitParameter("resourceBase");
        if (rb!=null)
        {
            if (_relativeResourceBase!=null)
                throw new  UnavailableException("resourceBase & relativeResourceBase");
            try{_resourceBase=_contextHandler.newResource(rb);}
            catch (Exception e)
            {
                LOG.warn(Log.EXCEPTION,e);
                throw new UnavailableException(e.toString());
            }
        }

        String css=getInitParameter("stylesheet");
        try
        {
            if(css!=null)
            {
                _stylesheet = Resource.newResource(css);
                if(!_stylesheet.exists())
                {
                    LOG.warn("!" + css);
                    _stylesheet = null;
                }
            }
            if(_stylesheet == null)
            {
                _stylesheet = Resource.newResource(this.getClass().getResource("/jetty-dir.css"));
            }
        }
        catch(Exception e)
        {
            LOG.warn(e.toString());
            LOG.debug(e);
        }

        String cc=getInitParameter("cacheControl");
        if (cc!=null)
            _cacheControl=new PreEncodedHttpField(HttpHeader.CACHE_CONTROL, cc);

        String resourceCache = getInitParameter("resourceCache");
        int max_cache_size=getInitInt("maxCacheSize", -2);
        int max_cached_file_size=getInitInt("maxCachedFileSize", -2);
        int max_cached_files=getInitInt("maxCachedFiles", -2);
        if (resourceCache!=null)
        {
            if (max_cache_size!=-1 || max_cached_file_size!= -2 || max_cached_files!=-2)
                LOG.debug("ignoring resource cache configuration, using resourceCache attribute");
            if (_relativeResourceBase!=null || _resourceBase!=null)
                throw new UnavailableException("resourceCache specified with resource bases");
            _cache=(ResourceCache)_servletContext.getAttribute(resourceCache);

            if (LOG.isDebugEnabled())
                LOG.debug("Cache {}={}",resourceCache,_contentFactory);
        }

        _etags = getInitBoolean("etags",_etags);

        try
        {
            if (_cache==null && (max_cached_files!=-2 || max_cache_size!=-2 || max_cached_file_size!=-2))
            {
                _cache = new ResourceCache(null,this,_mimeTypes,_useFileMappedBuffer,_etags,_gzip);
                if (max_cache_size>=0)
                    _cache.setMaxCacheSize(max_cache_size);
                if (max_cached_file_size>=-1)
                    _cache.setMaxCachedFileSize(max_cached_file_size);
                if (max_cached_files>=-1)
                    _cache.setMaxCachedFiles(max_cached_files);
                _servletContext.setAttribute(resourceCache==null?"resourceCache":resourceCache,_cache);
            }
        }
        catch (Exception e)
        {
            LOG.warn(Log.EXCEPTION,e);
            throw new UnavailableException(e.toString());
        }

        _contentFactory=_cache==null?new ResourceContentFactory(this,_mimeTypes,-1,_gzip):_cache; // TODO pass a buffer size

        _gzipEquivalentFileExtensions = new ArrayList<String>();
        String otherGzipExtensions = getInitParameter("otherGzipFileExtensions");
        if (otherGzipExtensions != null)
        {
            //comma separated list
            StringTokenizer tok = new StringTokenizer(otherGzipExtensions,",",false);
            while (tok.hasMoreTokens())
            {
                String s = tok.nextToken().trim();
                _gzipEquivalentFileExtensions.add((s.charAt(0)=='.'?s:"."+s));
            }
        }
        else
        {
            //.svgz files are gzipped svg files and must be served with Content-Encoding:gzip
            _gzipEquivalentFileExtensions.add(".svgz");   
        }

        _servletHandler= _contextHandler.getChildHandlerByClass(ServletHandler.class);
        for (ServletHolder h :_servletHandler.getServlets())
            if (h.getServletInstance()==this)
                _defaultHolder=h;

        if (LOG.isDebugEnabled())
            LOG.debug("resource base = "+_resourceBase);
    }

    /**
     * Compute the field _contextHandler.<br>
     * In the case where the DefaultServlet is deployed on the HttpService it is likely that
     * this method needs to be overwritten to unwrap the ServletContext facade until we reach
     * the original jetty's ContextHandler.
     * @param servletContext The servletContext of this servlet.
     * @return the jetty's ContextHandler for this servletContext.
     */
    protected ContextHandler initContextHandler(ServletContext servletContext)
    {
        ContextHandler.Context scontext=ContextHandler.getCurrentContext();
        if (scontext==null)
        {
            if (servletContext instanceof ContextHandler.Context)
                return ((ContextHandler.Context)servletContext).getContextHandler();
            else
                throw new IllegalArgumentException("The servletContext " + servletContext + " " +
                    servletContext.getClass().getName() + " is not " + ContextHandler.Context.class.getName());
        }
        else
            return ContextHandler.getCurrentContext().getContextHandler();
    }

    /* ------------------------------------------------------------ */
    @Override
    public String getInitParameter(String name)
    {
        String value=getServletContext().getInitParameter("org.eclipse.jetty.servlet.Default."+name);
        if (value==null)
            value=super.getInitParameter(name);
        return value;
    }

    /* ------------------------------------------------------------ */
    private boolean getInitBoolean(String name, boolean dft)
    {
        String value=getInitParameter(name);
        if (value==null || value.length()==0)
            return dft;
        return (value.startsWith("t")||
                value.startsWith("T")||
                value.startsWith("y")||
                value.startsWith("Y")||
                value.startsWith("1"));
    }

    /* ------------------------------------------------------------ */
    private int getInitInt(String name, int dft)
    {
        String value=getInitParameter(name);
        if (value==null)
            value=getInitParameter(name);
        if (value!=null && value.length()>0)
            return Integer.parseInt(value);
        return dft;
    }

    /* ------------------------------------------------------------ */
    /** get Resource to serve.
     * Map a path to a resource. The default implementation calls
     * HttpContext.getResource but derived servlets may provide
     * their own mapping.
     * @param pathInContext The path to find a resource for.
     * @return The resource to serve.
     */
    @Override
    public Resource getResource(String pathInContext)
    {
        Resource r=null;
        if (_relativeResourceBase!=null)
            pathInContext=URIUtil.addPaths(_relativeResourceBase,pathInContext);

        try
        {
            if (_resourceBase!=null)
            {
                r = _resourceBase.addPath(pathInContext);
                if (!_contextHandler.checkAlias(pathInContext,r))
                    r=null;
            }
            else if (_servletContext instanceof ContextHandler.Context)
            {
                r = _contextHandler.getResource(pathInContext);
            }
            else
            {
                URL u = _servletContext.getResource(pathInContext);
                r = _contextHandler.newResource(u);
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Resource "+pathInContext+"="+r);
        }
        catch (IOException e)
        {
            LOG.ignore(e);
        }

        if((r==null || !r.exists()) && pathInContext.endsWith("/jetty-dir.css"))
            r=_stylesheet;

        return r;
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
    throws ServletException, IOException
    {
        String servletPath=null;
        String pathInfo=null;
        Enumeration<String> reqRanges = null;
        boolean included =request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI)!=null;
        if (included)
        {
            servletPath=(String)request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH);
            pathInfo=(String)request.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO);
            if (servletPath==null)
            {
                servletPath=request.getServletPath();
                pathInfo=request.getPathInfo();
            }
        }
        else
        {
            servletPath = _pathInfoOnly?"/":request.getServletPath();
            pathInfo = request.getPathInfo();

            // Is this a Range request?
            reqRanges = request.getHeaders(HttpHeader.RANGE.asString());
            if (!hasDefinedRange(reqRanges))
                reqRanges = null;
        }

        String pathInContext=URIUtil.addPaths(servletPath,pathInfo);
        boolean endsWithSlash=(pathInfo==null?request.getServletPath():pathInfo).endsWith(URIUtil.SLASH);
        boolean gzippable=_gzip && !endsWithSlash && !included && reqRanges==null;
        
        HttpContent content=null;
        boolean release_content=true;
        try
        {
            // Find the content
            content=_contentFactory.getContent(pathInContext);
            if (LOG.isDebugEnabled())
                LOG.info("content={}",content);
            
            // Not found?
            if (content==null || !content.getResource().exists())
            {
                if (included)
                    throw new FileNotFoundException("!" + pathInContext);
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            
            // Directory?
            if (content.getResource().isDirectory())
            {
                sendWelcome(content,pathInContext,endsWithSlash,included,request,response);
                return;
            }
            
            // Strip slash?
            if (endsWithSlash && pathInContext.length()>1)
            {
                String q=request.getQueryString();
                pathInContext=pathInContext.substring(0,pathInContext.length()-1);
                if (q!=null&&q.length()!=0)
                    pathInContext+="?"+q;
                response.sendRedirect(response.encodeRedirectURL(URIUtil.addPaths(_servletContext.getContextPath(),pathInContext)));
                return;
            }
            
            // Conditional response?
            if (!included && !passConditionalHeaders(request,response,content))
                return;
                
            // Gzip?
            HttpContent gzip_content = gzippable?content.getGzipContent():null;
            if (gzip_content!=null)
            {
                // Tell caches that response may vary by accept-encoding
                response.addHeader(HttpHeader.VARY.asString(),HttpHeader.ACCEPT_ENCODING.asString());
                
                // Does the client accept gzip?
                String accept=request.getHeader(HttpHeader.ACCEPT_ENCODING.asString());
                if (accept!=null && accept.indexOf("gzip")>=0)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("gzip={}",gzip_content);
                    content=gzip_content;
                }
            }

            // TODO this should be done by HttpContent#getContentEncoding
            if (isGzippedContent(pathInContext))
                response.setHeader(HttpHeader.CONTENT_ENCODING.asString(),"gzip");
                
            // Send the data
            release_content=sendData(request,response,included,content,reqRanges);
            
        }
        catch(IllegalArgumentException e)
        {
            LOG.warn(Log.EXCEPTION,e);
            if(!response.isCommitted())
                response.sendError(500, e.getMessage());
        }
        finally
        {
            if (release_content)
            {
                if (content!=null)
                    content.release();
            }
        }

    }

    protected void sendWelcome(HttpContent content, String pathInContext, boolean endsWithSlash, boolean included, HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException
    {                
        // Redirect to directory
        if (!endsWithSlash || (pathInContext.length()==1 && request.getAttribute("org.eclipse.jetty.server.nullPathInfo")!=null))
        {
            StringBuffer buf=request.getRequestURL();
            synchronized(buf)
            {
                int param=buf.lastIndexOf(";");
                if (param<0)
                    buf.append('/');
                else
                    buf.insert(param,'/');
                String q=request.getQueryString();
                if (q!=null&&q.length()!=0)
                {
                    buf.append('?');
                    buf.append(q);
                }
                response.setContentLength(0);
                response.sendRedirect(response.encodeRedirectURL(buf.toString()));
            }
            return;
        }
        
        // look for a welcome file
        String welcome=getWelcomeFile(pathInContext);
        if (welcome!=null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("welcome={}",welcome);
            if (_redirectWelcome)
            {
                // Redirect to the index
                response.setContentLength(0);
                String q=request.getQueryString();
                if (q!=null&&q.length()!=0)
                    response.sendRedirect(response.encodeRedirectURL(URIUtil.addPaths( _servletContext.getContextPath(),welcome)+"?"+q));
                else
                    response.sendRedirect(response.encodeRedirectURL(URIUtil.addPaths( _servletContext.getContextPath(),welcome)));
            }
            else
            {
                // Forward to the index
                RequestDispatcher dispatcher=request.getRequestDispatcher(welcome);
                if (dispatcher!=null)
                {
                    if (included)
                        dispatcher.include(request,response);
                    else
                    {
                        request.setAttribute("org.eclipse.jetty.server.welcome",welcome);
                        dispatcher.forward(request,response);
                    }
                }
            }
            return;
        }
         
        if (included || passConditionalHeaders(request,response, content))
            sendDirectory(request,response,content.getResource(),pathInContext);
    }

    /* ------------------------------------------------------------ */
    protected boolean isGzippedContent(String path)
    {
        if (path == null) return false;
      
        for (String suffix:_gzipEquivalentFileExtensions)
            if (path.endsWith(suffix))
                return true;
        return false;
    }

    /* ------------------------------------------------------------ */
    private boolean hasDefinedRange(Enumeration<String> reqRanges)
    {
        return (reqRanges!=null && reqRanges.hasMoreElements());
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
    throws ServletException, IOException
    {
        doGet(request,response);
    }

    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see javax.servlet.http.HttpServlet#doTrace(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    protected void doTrace(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp)
    throws ServletException, IOException
    {
        resp.setHeader("Allow", "GET,HEAD,POST,OPTIONS");
    }

    /* ------------------------------------------------------------ */
    /**
     * Finds a matching welcome file for the supplied {@link Resource}. This will be the first entry in the list of
     * configured {@link #_welcomes welcome files} that existing within the directory referenced by the <code>Resource</code>.
     * If the resource is not a directory, or no matching file is found, then it may look for a valid servlet mapping.
     * If there is none, then <code>null</code> is returned.
     * The list of welcome files is read from the {@link ContextHandler} for this servlet, or
     * <code>"index.jsp" , "index.html"</code> if that is <code>null</code>.
     * @param resource
     * @return The path of the matching welcome file in context or null.
     * @throws IOException
     * @throws MalformedURLException
     */
    private String getWelcomeFile(String pathInContext) throws MalformedURLException, IOException
    {
        if (_welcomes==null)
            return null;

        String welcome_servlet=null;
        for (int i=0;i<_welcomes.length;i++)
        {
            String welcome_in_context=URIUtil.addPaths(pathInContext,_welcomes[i]);
            Resource welcome=getResource(welcome_in_context);
            if (welcome!=null && welcome.exists())
                return _welcomes[i];

            if ((_welcomeServlets || _welcomeExactServlets) && welcome_servlet==null)
            {
                MappedResource<ServletHolder> entry=_servletHandler.getHolderEntry(welcome_in_context);
                if (entry!=null && entry.getResource()!=_defaultHolder &&
                        (_welcomeServlets || (_welcomeExactServlets && entry.getPathSpec().getDeclaration().equals(welcome_in_context))))
                    welcome_servlet=welcome_in_context;

            }
        }
        return welcome_servlet;
    }

    /* ------------------------------------------------------------ */
    /* Check modification date headers.
     */
    protected boolean passConditionalHeaders(HttpServletRequest request,HttpServletResponse response, HttpContent content)
    throws IOException
    {
        try
        {
            String ifm=null;
            String ifnm=null;
            String ifms=null;
            long ifums=-1;
            
            if (request instanceof Request)
            {
                // Find multiple fields by iteration as an optimization 
                HttpFields fields = ((Request)request).getHttpFields();
                for (int i=fields.size();i-->0;)
                {
                    HttpField field=fields.getField(i);
                    if (field.getHeader() != null)
                    {
                        switch (field.getHeader())
                        {
                            case IF_MATCH:
                                ifm=field.getValue();
                                break;
                            case IF_NONE_MATCH:
                                ifnm=field.getValue();
                                break;
                            case IF_MODIFIED_SINCE:
                                ifms=field.getValue();
                                break;
                            case IF_UNMODIFIED_SINCE:
                                ifums=DateParser.parseDate(field.getValue());
                                break;
                            default:
                        }
                    }
                }
            }
            else
            {
                ifm=request.getHeader(HttpHeader.IF_MATCH.asString());
                ifnm=request.getHeader(HttpHeader.IF_NONE_MATCH.asString());
                ifms=request.getHeader(HttpHeader.IF_MODIFIED_SINCE.asString());
                ifums=request.getDateHeader(HttpHeader.IF_UNMODIFIED_SINCE.asString());
            }
            
            if (!HttpMethod.HEAD.is(request.getMethod()))
            {
                if (_etags)
                {
                    String etag=content.getETagValue();
                    if (ifm!=null)
                    {
                        boolean match=false;
                        if (etag!=null)
                        {
                            QuotedStringTokenizer quoted = new QuotedStringTokenizer(ifm,", ",false,true);
                            while (!match && quoted.hasMoreTokens())
                            {
                                String tag = quoted.nextToken();
                                if (etag.equals(tag) || tag.endsWith(ETAG_GZIP_QUOTE) && etag.equals(removeGzipFromETag(tag)))
                                    match=true;
                            }
                        }

                        if (!match)
                        {
                            response.setStatus(HttpServletResponse.SC_PRECONDITION_FAILED);
                            return false;
                        }
                    }
                    
                    if (ifnm!=null && etag!=null)
                    {
                        // Handle special case of exact match OR gzip exact match
                        if (etag.equals(ifnm) || ifnm.endsWith(ETAG_GZIP_QUOTE) && ifnm.indexOf(',')<0 && etag.equals(removeGzipFromETag(etag)))
                        {
                            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                            response.setHeader(HttpHeader.ETAG.asString(),ifnm);
                            return false;
                        }
                        
                        // Handle list of tags
                        QuotedStringTokenizer quoted = new QuotedStringTokenizer(ifnm,", ",false,true);
                        while (quoted.hasMoreTokens())
                        {
                            String tag = quoted.nextToken();
                            if (etag.equals(tag) || tag.endsWith(ETAG_GZIP_QUOTE) && etag.equals(removeGzipFromETag(tag))) 
                            {
                                response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                                response.setHeader(HttpHeader.ETAG.asString(),tag);
                                return false;
                            }
                        }
                        
                        // If etag requires content to be served, then do not check if-modified-since
                        return true;
                    }
                }
                
                // Handle if modified since
                if (ifms!=null)
                {
                    //Get jetty's Response impl
                    String mdlm=content.getLastModifiedValue();
                    if (mdlm!=null && ifms.equals(mdlm))
                    {
                        response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                        if (_etags)
                            response.setHeader(HttpHeader.ETAG.asString(),content.getETagValue());
                        response.flushBuffer();
                        return false;
                    }

                    long ifmsl=request.getDateHeader(HttpHeader.IF_MODIFIED_SINCE.asString());
                    if (ifmsl!=-1 && content.getResource().lastModified()/1000 <= ifmsl/1000)
                    { 
                        response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                        if (_etags)
                            response.setHeader(HttpHeader.ETAG.asString(),content.getETagValue());
                        response.flushBuffer();
                        return false;
                    }
                }

                // Parse the if[un]modified dates and compare to resource
                if (ifums!=-1 && content.getResource().lastModified()/1000 > ifums/1000)
                {
                    response.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
                    return false;
                }

            }
        }
        catch(IllegalArgumentException iae)
        {
            if(!response.isCommitted())
                response.sendError(400, iae.getMessage());
            throw iae;
        }
        return true;
    }


    /* ------------------------------------------------------------------- */
    protected void sendDirectory(HttpServletRequest request,
            HttpServletResponse response,
            Resource resource,
            String pathInContext)
    throws IOException
    {
        if (!_dirAllowed)
        {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        byte[] data=null;
        String base = URIUtil.addPaths(request.getRequestURI(),URIUtil.SLASH);

        //If the DefaultServlet has a resource base set, use it
        if (_resourceBase != null)
        {
            // handle ResourceCollection
            if (_resourceBase instanceof ResourceCollection)
                resource=_resourceBase.addPath(pathInContext);
        }
        //Otherwise, try using the resource base of its enclosing context handler
        else if (_contextHandler.getBaseResource() instanceof ResourceCollection)
            resource=_contextHandler.getBaseResource().addPath(pathInContext);

        String dir = resource.getListHTML(base,pathInContext.length()>1);
        if (dir==null)
        {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
            "No directory");
            return;
        }

        data=dir.getBytes("utf-8");
        response.setContentType("text/html;charset=utf-8");
        response.setContentLength(data.length);
        response.getOutputStream().write(data);
    }

    /* ------------------------------------------------------------ */
    protected boolean sendData(HttpServletRequest request,
            HttpServletResponse response,
            boolean include,
            final HttpContent content,
            Enumeration<String> reqRanges)
    throws IOException
    {
        final long content_length = content.getContentLengthValue();
        
        // Get the output stream (or writer)
        OutputStream out =null;
        boolean written;
        try
        {
            out = response.getOutputStream();

            // has something already written to the response?
            written = out instanceof HttpOutput
                ? ((HttpOutput)out).isWritten()
                : true;
        }
        catch(IllegalStateException e)
        {
            out = new WriterOutputStream(response.getWriter());
            written=true; // there may be data in writer buffer, so assume written
        }
        
        if (LOG.isDebugEnabled())
            LOG.debug(String.format("sendData content=%s out=%s async=%b",content,out,request.isAsyncSupported()));

        if ( reqRanges == null || !reqRanges.hasMoreElements() || content_length<0)
        {
            //  if there were no ranges, send entire entity
            if (include)
            {
                // write without headers
                content.getResource().writeTo(out,0,content_length);
            }
            // else if we can't do a bypass write because of wrapping
            else if (written || !(out instanceof HttpOutput))
            {
                // write normally
                putHeaders(response,content,written?-1:0);
                ByteBuffer buffer = content.getIndirectBuffer();
                if (buffer!=null)
                    BufferUtil.writeTo(buffer,out);
                else
                    content.getResource().writeTo(out,0,content_length);
            }
            // else do a bypass write
            else
            {
                // write the headers
                putHeaders(response,content,0);

                // write the content asynchronously if supported
                if (request.isAsyncSupported())
                {
                    final AsyncContext context = request.startAsync();
                    context.setTimeout(0);

                    ((HttpOutput)out).sendContent(content,new Callback()
                    {
                        @Override
                        public void succeeded()
                        {   
                            context.complete();
                            content.release();
                        }

                        @Override
                        public void failed(Throwable x)
                        {
                            if (x instanceof IOException)
                                LOG.debug(x);
                            else
                                LOG.warn(x);
                            context.complete();
                            content.release();
                        }
                        
                        @Override
                        public String toString() 
                        {
                            return String.format("DefaultServlet@%x$CB", DefaultServlet.this.hashCode());
                        }
                    });
                    return false;
                }
                // otherwise write content blocking
                ((HttpOutput)out).sendContent(content);
            }
        }
        else
        {
            // Parse the satisfiable ranges
            List<InclusiveByteRange> ranges =InclusiveByteRange.satisfiableRanges(reqRanges,content_length);

            //  if there are no satisfiable ranges, send 416 response
            if (ranges==null || ranges.size()==0)
            {
                putHeaders(response,content,0);
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                response.setHeader(HttpHeader.CONTENT_RANGE.asString(),
                        InclusiveByteRange.to416HeaderRangeString(content_length));
                content.getResource().writeTo(out,0,content_length);
                return true;
            }

            //  if there is only a single valid range (must be satisfiable
            //  since were here now), send that range with a 216 response
            if ( ranges.size()== 1)
            {
                InclusiveByteRange singleSatisfiableRange = ranges.get(0);
                long singleLength = singleSatisfiableRange.getSize(content_length);
                putHeaders(response,content,singleLength);
                response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                if (!response.containsHeader(HttpHeader.DATE.asString()))
                    response.addDateHeader(HttpHeader.DATE.asString(),System.currentTimeMillis());
                response.setHeader(HttpHeader.CONTENT_RANGE.asString(),
                        singleSatisfiableRange.toHeaderRangeString(content_length));
                content.getResource().writeTo(out,singleSatisfiableRange.getFirst(content_length),singleLength);
                return true;
            }

            //  multiple non-overlapping valid ranges cause a multipart
            //  216 response which does not require an overall
            //  content-length header
            //
            putHeaders(response,content,-1);
            String mimetype=(content==null?null:content.getContentTypeValue());
            if (mimetype==null)
                LOG.warn("Unknown mimetype for "+request.getRequestURI());
            MultiPartOutputStream multi = new MultiPartOutputStream(out);
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            if (!response.containsHeader(HttpHeader.DATE.asString()))
                response.addDateHeader(HttpHeader.DATE.asString(),System.currentTimeMillis());

            // If the request has a "Request-Range" header then we need to
            // send an old style multipart/x-byteranges Content-Type. This
            // keeps Netscape and acrobat happy. This is what Apache does.
            String ctp;
            if (request.getHeader(HttpHeader.REQUEST_RANGE.asString())!=null)
                ctp = "multipart/x-byteranges; boundary=";
            else
                ctp = "multipart/byteranges; boundary=";
            response.setContentType(ctp+multi.getBoundary());

            InputStream in=content.getResource().getInputStream();
            long pos=0;

            // calculate the content-length
            int length=0;
            String[] header = new String[ranges.size()];
            for (int i=0;i<ranges.size();i++)
            {
                InclusiveByteRange ibr = ranges.get(i);
                header[i]=ibr.toHeaderRangeString(content_length);
                length+=
                    ((i>0)?2:0)+
                    2+multi.getBoundary().length()+2+
                    (mimetype==null?0:HttpHeader.CONTENT_TYPE.asString().length()+2+mimetype.length())+2+
                    HttpHeader.CONTENT_RANGE.asString().length()+2+header[i].length()+2+
                    2+
                    (ibr.getLast(content_length)-ibr.getFirst(content_length))+1;
            }
            length+=2+2+multi.getBoundary().length()+2+2;
            response.setContentLength(length);

            for (int i=0;i<ranges.size();i++)
            {
                InclusiveByteRange ibr =  ranges.get(i);
                multi.startPart(mimetype,new String[]{HttpHeader.CONTENT_RANGE+": "+header[i]});

                long start=ibr.getFirst(content_length);
                long size=ibr.getSize(content_length);
                if (in!=null)
                {
                    // Handle non cached resource
                    if (start<pos)
                    {
                        in.close();
                        in=content.getResource().getInputStream();
                        pos=0;
                    }
                    if (pos<start)
                    {
                        in.skip(start-pos);
                        pos=start;
                    }
                    
                    IO.copy(in,multi,size);
                    pos+=size;
                }
                else
                    // Handle cached resource
                    content.getResource().writeTo(multi,start,size);
            }
            if (in!=null)
                in.close();
            multi.close();
        }
        return true;
    }

    /* ------------------------------------------------------------ */
    protected void putHeaders(HttpServletResponse response,HttpContent content, long contentLength)
    {
        if (response instanceof Response)
        {
            Response r = (Response)response;
            r.putHeaders(content,contentLength,_etags);
            HttpFields f = r.getHttpFields();
            if (_acceptRanges)
                f.put(ACCEPT_RANGES);

            if (_cacheControl!=null)
                f.put(_cacheControl);
        }
        else
        {
            Response.putHeaders(response,content,contentLength,_etags);
            if (_acceptRanges)
                response.setHeader(ACCEPT_RANGES.getName(),ACCEPT_RANGES.getValue());

            if (_cacheControl!=null)
                response.setHeader(_cacheControl.getName(),_cacheControl.getValue());
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.Servlet#destroy()
     */
    @Override
    public void destroy()
    {
        if (_cache!=null)
            _cache.flushCache();
        super.destroy();
    }

}
