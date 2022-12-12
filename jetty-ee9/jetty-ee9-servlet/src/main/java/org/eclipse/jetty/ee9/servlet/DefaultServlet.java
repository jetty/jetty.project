//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.servlet;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringTokenizer;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.nested.ContextHandler;
import org.eclipse.jetty.ee9.nested.ResourceService;
import org.eclipse.jetty.ee9.nested.ResourceService.WelcomeFactory;
import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.content.CachingHttpContentFactory;
import org.eclipse.jetty.http.content.FileMappingHttpContentFactory;
import org.eclipse.jetty.http.content.HttpContent;
import org.eclipse.jetty.http.content.PreCompressedHttpContentFactory;
import org.eclipse.jetty.http.content.ResourceHttpContentFactory;
import org.eclipse.jetty.http.content.StaticContentFactory;
import org.eclipse.jetty.http.content.ValidatingCachingHttpContentFactory;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.NoopByteBufferPool;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *                    Default is false.
 *
 *                    This must be false if you want directory listings,
 *                    but have index.jsp in your welcome file list.
 *
 *  redirectWelcome   If true, welcome files are redirected rather than
 *                    forwarded to.
 *
 *  gzip              If set to true, then static content will be served as
 *                    gzip content encoded if a matching resource is
 *                    found ending with ".gz" (default false)
 *                    (deprecated: use precompressed)
 *
 *  precompressed     If set to a comma separated list of encoding types (that may be
 *                    listed in a requests Accept-Encoding header) to file
 *                    extension mappings to look for and serve. For example:
 *                    "br=.br,gzip=.gz,bzip2=.bz".
 *                    If set to a boolean True, then a default set of compressed formats
 *                    will be used, otherwise no precompressed formats.
 *
 *  baseResource      Set to replace the context resource base
 *
 *  resourceCache     If set, this is a context attribute name, which the servlet
 *                    will use to look for a shared ResourceCache instance.
 *
 *  relativeBaseResource
 *                    Set with a pathname relative to the base of the
 *                    servlet context root. Useful for only serving static content out
 *                    of only specific subdirectories.
 *
 *  pathInfoOnly      If true, only the path info will be applied to the baseResource
 *
 *  stylesheet        Set with the location of an optional stylesheet that will be used
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
 *                    by eg webdefault-ee9.xml
 *
 *  cacheControl      If set, all static content will have this value set as the cache-control
 *                    header.
 *
 *  otherGzipFileExtensions
 *                    Other file extensions that signify that a file is already compressed. Eg ".svgz"
 *
 *  encodingHeaderCacheSize
 *                    Max entries in a cache of ACCEPT-ENCODING headers.
 * </pre>
 */
public class DefaultServlet extends HttpServlet implements ResourceFactory, WelcomeFactory
{
    public static final String CONTEXT_INIT = "org.eclipse.jetty.servlet.Default.";

    private static final Logger LOG = LoggerFactory.getLogger(DefaultServlet.class);

    private static final long serialVersionUID = 4930458713846881193L;

    private final ResourceService _resourceService;
    private ServletContext _servletContext;
    private ContextHandler _contextHandler;

    private boolean _welcomeServlets = false;
    private boolean _welcomeExactServlets = false;
    private Resource _baseResource;
    private CachingHttpContentFactory _cachingContentFactory;
    private MimeTypes _mimeTypes;
    private String[] _welcomes;
    private ResourceFactory.Closeable _resourceFactory;
    private Resource _styleSheet;
    private boolean _useFileMappedBuffer = false;
    private String _relativeBaseResource;
    private ServletHandler _servletHandler;

    public DefaultServlet(ResourceService resourceService)
    {
        _resourceService = resourceService;
    }

    public DefaultServlet()
    {
        this(new ResourceService());
    }

    @Override
    public void init()
        throws UnavailableException
    {
        _servletContext = getServletContext();
        _contextHandler = initContextHandler(_servletContext);
        _resourceFactory = ResourceFactory.closeable();

        _mimeTypes = _contextHandler.getMimeTypes();

        _welcomes = _contextHandler.getWelcomeFiles();
        if (_welcomes == null)
            _welcomes = new String[]{"index.html", "index.jsp"};

        _resourceService.setAcceptRanges(getInitBoolean("acceptRanges", _resourceService.isAcceptRanges()));
        _resourceService.setDirAllowed(getInitBoolean("dirAllowed", _resourceService.isDirAllowed()));
        _resourceService.setRedirectWelcome(getInitBoolean("redirectWelcome", _resourceService.isRedirectWelcome()));
        _resourceService.setPrecompressedFormats(parsePrecompressedFormats(getInitParameter("precompressed"), getInitBoolean("gzip"), _resourceService.getPrecompressedFormats()));
        _resourceService.setPathInfoOnly(getInitBoolean("pathInfoOnly", _resourceService.isPathInfoOnly()));
        _resourceService.setEtags(getInitBoolean("etags", _resourceService.isEtags()));

        if ("exact".equals(getInitParameter("welcomeServlets")))
        {
            _welcomeExactServlets = true;
            _welcomeServlets = false;
        }
        else
            _welcomeServlets = getInitBoolean("welcomeServlets", _welcomeServlets);

        _useFileMappedBuffer = getInitBoolean("useFileMappedBuffer", _useFileMappedBuffer);

        _relativeBaseResource = getInitParameter("relativeBaseResource", "relativeResourceBase");

        String br = getInitParameter("baseResource", "resourceBase");
        if (br != null)
        {
            if (_relativeBaseResource != null)
                throw new UnavailableException("baseResource & relativeBaseResource");
            try
            {
                _baseResource = _contextHandler.newResource(br);
            }
            catch (Exception e)
            {
                LOG.warn("Unable to create baseResource from {}", br, e);
                throw new UnavailableException(e.toString());
            }
        }

        String stylesheet = getInitParameter("stylesheet");
        try
        {
            if (stylesheet != null)
            {
                _styleSheet = _resourceFactory.newResource(stylesheet);
                if (Resources.missing(_styleSheet))
                {
                    LOG.warn("Stylesheet {} does not exist", stylesheet);
                    _styleSheet = null;
                }
            }
            if (_styleSheet == null)
            {
                _styleSheet = _contextHandler.getServer().getDefaultStyleSheet();
            }
        }
        catch (Exception e)
        {
            if (LOG.isDebugEnabled())
                LOG.warn("Unable to use stylesheet: {}", stylesheet, e);
            else
                LOG.warn("Unable to use stylesheet: {} - {}", stylesheet, e.toString());
        }

        int encodingHeaderCacheSize = getInitInt("encodingHeaderCacheSize", -1);
        if (encodingHeaderCacheSize >= 0)
            _resourceService.setEncodingCacheSize(encodingHeaderCacheSize);

        String cc = getInitParameter("cacheControl");
        if (cc != null)
            _resourceService.setCacheControl(new PreEncodedHttpField(HttpHeader.CACHE_CONTROL, cc));

        // Try to get factory from ServletContext attribute.
        HttpContent.Factory contentFactory = (HttpContent.Factory)getServletContext().getAttribute(HttpContent.Factory.class.getName());
        if (contentFactory == null)
        {
            contentFactory = new ResourceHttpContentFactory(this, _mimeTypes);
            if (_useFileMappedBuffer)
                contentFactory = new FileMappingHttpContentFactory(contentFactory);
            contentFactory = new StaticContentFactory(contentFactory, _styleSheet);
            contentFactory = new PreCompressedHttpContentFactory(contentFactory, _resourceService.getPrecompressedFormats());

            int maxCacheSize = getInitInt("maxCacheSize", -2);
            int maxCachedFileSize = getInitInt("maxCachedFileSize", -2);
            int maxCachedFiles = getInitInt("maxCachedFiles", -2);
            long cacheValidationTime = getInitParameter("cacheValidationTime") != null ? Long.parseLong(getInitParameter("cacheValidationTime")) : -2;
            if (maxCachedFiles != -2 || maxCacheSize != -2 || maxCachedFileSize != -2 || cacheValidationTime != -2)
            {
                ByteBufferPool byteBufferPool = getByteBufferPool(_contextHandler);
                _cachingContentFactory = new ValidatingCachingHttpContentFactory(contentFactory,
                    (cacheValidationTime > -2) ? cacheValidationTime : Duration.ofSeconds(1).toMillis(), byteBufferPool);
                contentFactory = _cachingContentFactory;
                if (maxCacheSize >= 0)
                    _cachingContentFactory.setMaxCacheSize(maxCacheSize);
                if (maxCachedFileSize >= -1)
                    _cachingContentFactory.setMaxCachedFileSize(maxCachedFileSize);
                if (maxCachedFiles >= -1)
                    _cachingContentFactory.setMaxCachedFiles(maxCachedFiles);
            }
        }
        _resourceService.setHttpContentFactory(contentFactory);
        _resourceService.setWelcomeFactory(this);

        List<String> gzipEquivalentFileExtensions = new ArrayList<>();
        String otherGzipExtensions = getInitParameter("otherGzipFileExtensions");
        if (otherGzipExtensions != null)
        {
            //comma separated list
            StringTokenizer tok = new StringTokenizer(otherGzipExtensions, ",", false);
            while (tok.hasMoreTokens())
            {
                String s = tok.nextToken().trim();
                gzipEquivalentFileExtensions.add((s.charAt(0) == '.' ? s : "." + s));
            }
        }
        else
        {
            //.svgz files are gzipped svg files and must be served with Content-Encoding:gzip
            gzipEquivalentFileExtensions.add(".svgz");
        }
        _resourceService.setGzipEquivalentFileExtensions(gzipEquivalentFileExtensions);

        _servletHandler = _contextHandler.getChildHandlerByClass(ServletHandler.class);

        if (LOG.isDebugEnabled())
            LOG.debug("resource base = {}", _baseResource);
    }

    private static ByteBufferPool getByteBufferPool(ContextHandler contextHandler)
    {
        if (contextHandler == null)
            return new NoopByteBufferPool();
        Server server = contextHandler.getServer();
        if (server == null)
            return new NoopByteBufferPool();
        ByteBufferPool byteBufferPool = server.getBean(ByteBufferPool.class);
        return (byteBufferPool == null) ? new NoopByteBufferPool() : byteBufferPool;
    }

    private String getInitParameter(String name, String... deprecated)
    {
        String value = super.getInitParameter(name);
        if (value != null)
            return value;

        for (String d : deprecated)
        {
            value = super.getInitParameter(d);
            if (value != name)
            {
                LOG.warn("Deprecated {} used instead of {}", d, name);
                return value;
            }
        }

        return null;
    }

    private CompressedContentFormat[] parsePrecompressedFormats(String precompressed, Boolean gzip, CompressedContentFormat[] dft)
    {
        if (precompressed == null && gzip == null)
        {
            return dft;
        }
        List<CompressedContentFormat> ret = new ArrayList<>();
        if (precompressed != null && precompressed.indexOf('=') > 0)
        {
            for (String pair : precompressed.split(","))
            {
                String[] setting = pair.split("=");
                String encoding = setting[0].trim();
                String extension = setting[1].trim();
                ret.add(new CompressedContentFormat(encoding, extension));
                if (gzip == Boolean.TRUE && !ret.contains(CompressedContentFormat.GZIP))
                    ret.add(CompressedContentFormat.GZIP);
            }
        }
        else if (precompressed != null)
        {
            if (Boolean.parseBoolean(precompressed))
            {
                ret.add(CompressedContentFormat.BR);
                ret.add(CompressedContentFormat.GZIP);
            }
        }
        else if (gzip == Boolean.TRUE)
        {
            // gzip handling is for backwards compatibility with older Jetty
            ret.add(CompressedContentFormat.GZIP);
        }
        return ret.toArray(new CompressedContentFormat[ret.size()]);
    }

    /**
     * Compute the field _contextHandler.<br>
     * In the case where the DefaultServlet is deployed on the HttpService it is likely that
     * this method needs to be overwritten to unwrap the ServletContext facade until we reach
     * the original jetty's ContextHandler.
     *
     * @param servletContext The servletContext of this servlet.
     * @return the jetty's ContextHandler for this servletContext.
     */
    protected ContextHandler initContextHandler(ServletContext servletContext)
    {
        ContextHandler.APIContext scontext = ContextHandler.getCurrentContext();
        if (scontext == null)
        {
            if (servletContext instanceof ContextHandler.APIContext)
                return ((ContextHandler.APIContext)servletContext).getContextHandler();
            else
                throw new IllegalArgumentException("The servletContext " + servletContext + " " +
                    servletContext.getClass().getName() + " is not " + ContextHandler.APIContext.class.getName());
        }
        else
            return ContextHandler.getCurrentContext().getContextHandler();
    }

    @Override
    public String getInitParameter(String name)
    {
        String value = getServletContext().getInitParameter(CONTEXT_INIT + name);
        if (value == null)
            value = super.getInitParameter(name);
        return value;
    }

    private Boolean getInitBoolean(String name)
    {
        String value = getInitParameter(name);
        if (value == null || value.length() == 0)
            return null;
        return (value.startsWith("t") ||
            value.startsWith("T") ||
            value.startsWith("y") ||
            value.startsWith("Y") ||
            value.startsWith("1"));
    }

    private boolean getInitBoolean(String name, boolean dft)
    {
        return Optional.ofNullable(getInitBoolean(name)).orElse(dft);
    }

    private int getInitInt(String name, int dft)
    {
        String value = getInitParameter(name);
        if (value == null)
            value = getInitParameter(name);
        if (value != null && value.length() > 0)
            return Integer.parseInt(value);
        return dft;
    }

    @Override
    public Resource newResource(String resource)
    {
        return resolve(resource);
    }

    @Override
    public Resource newResource(URI uri)
    {
        // TODO optimised path for URI?
        return resolve(uri.toString());
    }

    /**
     * get Resource to serve.
     * Map a path to a resource. The default implementation calls
     * HttpContext.getResource but derived servlets may provide
     * their own mapping.
     *
     * @param subUriPath The path to find a resource for.
     * @return The resource to serve.
     */
    protected Resource resolve(String subUriPath)
    {
        if (!_contextHandler.isCanonicalEncodingURIs())
            subUriPath = URIUtil.encodePath(subUriPath);

        Resource r = null;
        if (_relativeBaseResource != null)
            subUriPath = URIUtil.addPaths(_relativeBaseResource, subUriPath);

        try
        {
            if (_baseResource != null)
            {
                r = _baseResource.resolve(subUriPath);
            }
            else if (_servletContext instanceof ContextHandler.APIContext)
            {
                r = _contextHandler.getResource(subUriPath);
            }
            else
            {
                return null;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Resource {}={}", subUriPath, r);
        }
        catch (IOException e)
        {
            LOG.trace("IGNORED", e);
        }

        if (Resources.missing(r) && subUriPath.endsWith("/jetty-dir.css"))
            r = _styleSheet;

        return r;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException
    {
        if (!_resourceService.doGet(request, response))
            response.sendError(404);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException
    {
        doGet(request, response);
    }

    @Override
    protected void doHead(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException
    {
        doGet(request, response);
    }

    @Override
    protected void doTrace(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException
    {
        response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException
    {
        response.setHeader("Allow", "GET,HEAD,POST,OPTIONS");
    }

    @Override
    public void destroy()
    {
        if (_cachingContentFactory != null)
            _cachingContentFactory.flushCache();
        super.destroy();
        IO.close(_resourceFactory);
    }

    @Override
    public String getWelcomeFile(String pathInContext)
    {
        if (_welcomes == null)
            return null;

        String welcomeServlet = null;
        for (String s : _welcomes)
        {
            String welcomeInContext = URIUtil.addPaths(pathInContext, s);
            Resource welcome = resolve(welcomeInContext);
            if (welcome != null && welcome.exists())
                return welcomeInContext;

            if ((_welcomeServlets || _welcomeExactServlets) && welcomeServlet == null)
            {
                ServletHandler.MappedServlet entry = _servletHandler.getMappedServlet(welcomeInContext);
                if (entry != null && entry.getServletHolder().getServletInstance() != this &&
                    (_welcomeServlets || (_welcomeExactServlets && entry.getPathSpec().getDeclaration().equals(welcomeInContext))))
                    welcomeServlet = welcomeInContext;
            }
        }
        return welcomeServlet;
    }
}
