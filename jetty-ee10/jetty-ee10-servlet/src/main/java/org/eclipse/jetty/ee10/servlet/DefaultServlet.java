//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.servlet;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.InvalidPathException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.content.FileMappingHttpContentFactory;
import org.eclipse.jetty.http.content.HttpContent;
import org.eclipse.jetty.http.content.PreCompressedHttpContentFactory;
import org.eclipse.jetty.http.content.ResourceHttpContentFactory;
import org.eclipse.jetty.http.content.ValidatingCachingHttpContentFactory;
import org.eclipse.jetty.http.content.VirtualHttpContentFactory;
import org.eclipse.jetty.io.ByteBufferInputStream;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.ResourceService;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The default Servlet, normally mapped to {@code /}, that handles static resources.</p>
 * <p>The following init parameters are supported:</p>
 * <dl>
 *   <dt>acceptRanges</dt>
 *   <dd>
 *     Use {@code true} to accept range requests, defaults to {@code true}.
 *   </dd>
 *   <dt>baseResource</dt>
 *   <dd>
 *     Defaults to the context's baseResource.
 *     The root directory to look for static resources.
 *   </dd>
 *   <dt>cacheControl</dt>
 *   <dd>
 *     The value of the {@code Cache-Control} header.
 *     If omitted, no {@code Cache-Control} header is generated in responses.
 *     By default is omitted.
 *   </dd>
 *   <dt>cacheValidationTime</dt>
 *   <dd>
 *     How long in milliseconds a resource is cached.
 *     If omitted, defaults to {@code 1000} ms.
 *     Use {@code -1} to cache forever or {@code 0} to not cache.
 *   </dd>
 *   <dt>dirAllowed</dt>
 *   <dd>
 *     Use {@code true} to serve directory listing if no welcome file is found.
 *     Otherwise responds with {@code 403 Forbidden}.
 *     Defaults to {@code true}.
 *   </dd>
 *   <dt>encodingHeaderCacheSize</dt>
 *   <dd>
 *     Max number of cached {@code Accept-Encoding} entries.
 *     Use {@code -1} for the default value (100), {@code 0} for no cache.
 *   </dd>
 *   <dt>etags</dt>
 *   <dd>
 *     Use {@code true} to generate ETags in responses.
 *     Defaults to {@code false}.
 *   </dd>
 *   <dt>maxCachedFiles</dt>
 *   <dd>
 *     The max number of cached static resources.
 *     Use {@code -1} for the default value (2048) or {@code 0} for no cache.
 *   </dd>
 *   <dt>maxCachedFileSize</dt>
 *   <dd>
 *     The max size in bytes of a single cached static resource.
 *     Use {@code -1} for the default value (128 MiB) or {@code 0} for no cache.
 *   </dd>
 *   <dt>maxCacheSize</dt>
 *   <dd>
 *     The max size in bytes of the cache for static resources.
 *     Use {@code -1} for the default value (256 MiB) or {@code 0} for no cache.
 *   </dd>
 *   <dt>otherGzipFileExtensions</dt>
 *   <dd>
 *     A comma-separated list of extensions of files whose content is implicitly
 *     gzipped.
 *     Defaults to {@code .svgz}.
 *   </dd>
 *   <dt>pathInfoOnly</dt>
 *   <dd>
 *     Use {@code true} to use only the request {@code pathInfo} to look for
 *     static resources.
 *     Defaults to {@code false}.
 *   </dd>
 *   <dt>precompressed</dt>
 *   <dd>
 *     Omitted by default, so that no pre-compressed content will be served.
 *     If set to {@code true}, the default set of pre-compressed formats will be used.
 *     Otherwise can be set to a comma-separated list of {@code encoding=extension} pairs,
 *     such as: {@code br=.br,gzip=.gz,bzip2=.bz}, where {@code encoding} is used as the
 *     value for the {@code Content-Encoding} header.
 *   </dd>
 *   <dt>redirectWelcome</dt>
 *   <dd>
 *     Use {@code true} to redirect welcome files, otherwise they are forwarded.
 *     Defaults to {@code false}.
 *   </dd>
 *   <dt>stylesheet</dt>
 *   <dd>
 *     Defaults to the {@code Server}'s default stylesheet, {@code jetty-dir.css}.
 *     The path of a custom stylesheet to style the directory listing HTML.
 *   </dd>
 *   <dt>useFileMappedBuffer</dt>
 *   <dd>
 *     Use {@code true} to use file mapping to serve static resources.
 *     Defaults to {@code false}.
 *   </dd>
 *   <dt>welcomeServlets</dt>
 *   <dd>
 *     Use {@code false} to only serve welcome resources from the file system.
 *     Use {@code true} to dispatch welcome resources to a matching Servlet
 *     (for example mapped to {@code *.welcome}), when the welcome resources
 *     does not exist on file system.
 *     Use {@code exact} to dispatch welcome resource to a Servlet whose mapping
 *     is exactly the same as the welcome resource (for example {@code /index.welcome}),
 *     when the welcome resources does not exist on file system.
 *     Defaults to {@code false}.
 *   </dd>
 * </dl>
 */
public class DefaultServlet extends HttpServlet
{
    private static final Logger LOG = LoggerFactory.getLogger(DefaultServlet.class);

    private ServletContextHandler _contextHandler;
    private ServletResourceService _resourceService;
    private WelcomeServletMode _welcomeServletMode;
    private Resource _baseResource;
    private boolean _isPathInfoOnly;

    public ResourceService getResourceService()
    {
        return _resourceService;
    }

    @Override
    public void init() throws ServletException
    {
        _contextHandler = initContextHandler(getServletContext());
        _resourceService = new ServletResourceService(_contextHandler);
        _resourceService.setWelcomeFactory(_resourceService);
        _baseResource = _contextHandler.getBaseResource();

        String rb = getInitParameter("baseResource", "resourceBase");
        if (rb != null)
        {
            try
            {
                _baseResource = Objects.requireNonNull(_contextHandler.newResource(rb));
            }
            catch (Exception e)
            {
                LOG.warn("Unable to create baseResource from {}", rb, e);
                throw new UnavailableException(e.toString());
            }
        }

        List<CompressedContentFormat> precompressedFormats = parsePrecompressedFormats(getInitParameter("precompressed"),
            getInitBoolean("gzip"), _resourceService.getPrecompressedFormats());

        // Try to get factory from ServletContext attribute.
        HttpContent.Factory contentFactory = (HttpContent.Factory)getServletContext().getAttribute(HttpContent.Factory.class.getName());
        if (contentFactory == null)
        {
            MimeTypes mimeTypes = _contextHandler.getMimeTypes();
            ResourceFactory resourceFactory = _baseResource != null ? ResourceFactory.of(_baseResource) : this::getResource;
            contentFactory = new ResourceHttpContentFactory(resourceFactory, mimeTypes);

            // Use the servers default stylesheet unless there is one explicitly set by an init param.
            Resource styleSheet = _contextHandler.getServer().getDefaultStyleSheet();
            String stylesheetParam = getInitParameter("stylesheet");
            if (stylesheetParam != null)
            {
                try
                {
                    HttpContent styleSheetContent = contentFactory.getContent(stylesheetParam);
                    Resource s = styleSheetContent == null ? null : styleSheetContent.getResource();
                    if (Resources.isReadableFile(s))
                        styleSheet = s;
                    else
                        LOG.warn("Stylesheet {} does not exist", stylesheetParam);
                }
                catch (Exception e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.warn("Unable to use stylesheet: {}", stylesheetParam, e);
                    else
                        LOG.warn("Unable to use stylesheet: {} - {}", stylesheetParam, e.toString());
                }
            }

            if (getInitBoolean("useFileMappedBuffer", false))
                contentFactory = new FileMappingHttpContentFactory(contentFactory);

            contentFactory = new VirtualHttpContentFactory(contentFactory, styleSheet, "text/css");
            contentFactory = new PreCompressedHttpContentFactory(contentFactory, precompressedFormats);

            int maxCacheSize = getInitInt("maxCacheSize", -2);
            int maxCachedFileSize = getInitInt("maxCachedFileSize", -2);
            int maxCachedFiles = getInitInt("maxCachedFiles", -2);
            long cacheValidationTime = getInitParameter("cacheValidationTime") != null ? Long.parseLong(getInitParameter("cacheValidationTime")) : -2;
            if (maxCachedFiles != -2 || maxCacheSize != -2 || maxCachedFileSize != -2 || cacheValidationTime != -2)
            {
                ByteBufferPool bufferPool = getByteBufferPool(_contextHandler);
                ValidatingCachingHttpContentFactory cached = new ValidatingCachingHttpContentFactory(contentFactory,
                    (cacheValidationTime > -2) ? cacheValidationTime : Duration.ofSeconds(1).toMillis(), bufferPool);
                contentFactory = cached;
                if (maxCacheSize >= 0)
                    cached.setMaxCacheSize(maxCacheSize);
                if (maxCachedFileSize >= 0)
                    cached.setMaxCachedFileSize(maxCachedFileSize);
                if (maxCachedFiles >= 0)
                    cached.setMaxCachedFiles(maxCachedFiles);
            }
        }
        _resourceService.setHttpContentFactory(contentFactory);

        if (_contextHandler.getWelcomeFiles() == null)
            _contextHandler.setWelcomeFiles(new String[]{"index.html", "index.jsp"});

        _resourceService.setAcceptRanges(getInitBoolean("acceptRanges", _resourceService.isAcceptRanges()));
        _resourceService.setDirAllowed(getInitBoolean("dirAllowed", _resourceService.isDirAllowed()));
        boolean redirectWelcome = getInitBoolean("redirectWelcome", false);
        _resourceService.setWelcomeMode(redirectWelcome ? ResourceService.WelcomeMode.REDIRECT : ResourceService.WelcomeMode.SERVE);
        _resourceService.setPrecompressedFormats(precompressedFormats);
        _resourceService.setEtags(getInitBoolean("etags", _resourceService.isEtags()));

        _isPathInfoOnly = getInitBoolean("pathInfoOnly", _isPathInfoOnly);

        _welcomeServletMode = WelcomeServletMode.NONE;
        String welcomeServlets = getInitParameter("welcomeServlets");
        if (welcomeServlets != null)
        {
            welcomeServlets = welcomeServlets.toLowerCase(Locale.ENGLISH);
            _welcomeServletMode = switch (welcomeServlets)
            {
                case "true" -> WelcomeServletMode.MATCH;
                case "exact" -> WelcomeServletMode.EXACT;
                default -> WelcomeServletMode.NONE;
            };
        }

        int encodingHeaderCacheSize = getInitInt("encodingHeaderCacheSize", -1);
        if (encodingHeaderCacheSize >= 0)
            _resourceService.setEncodingCacheSize(encodingHeaderCacheSize);

        String cc = getInitParameter("cacheControl");
        if (cc != null)
            _resourceService.setCacheControl(cc);

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
            // .svgz files are gzipped svg files and must be served with Content-Encoding:gzip
            gzipEquivalentFileExtensions.add(".svgz");
        }
        _resourceService.setGzipEquivalentFileExtensions(gzipEquivalentFileExtensions);

        if (LOG.isDebugEnabled())
        {
            LOG.debug("  .baseResource = {}", _baseResource);
            LOG.debug("  .resourceService = {}", _resourceService);
            LOG.debug("  .isPathInfoOnly = {}", _isPathInfoOnly);
            LOG.debug("  .welcomeServletMode = {}", _welcomeServletMode);
        }
    }

    private static ByteBufferPool getByteBufferPool(ContextHandler contextHandler)
    {
        if (contextHandler == null)
            return new ByteBufferPool.NonPooling();
        Server server = contextHandler.getServer();
        if (server == null)
            return new ByteBufferPool.NonPooling();
        return server.getByteBufferPool();
    }

    private String getInitParameter(String name, String... deprecated)
    {
        String value = super.getInitParameter(name);
        if (value != null)
            return value;

        for (String d : deprecated)
        {
            value = super.getInitParameter(d);
            if (value != null)
            {
                LOG.warn("Deprecated {} used instead of {}", d, name);
                return value;
            }
        }

        return null;
    }

    private List<CompressedContentFormat> parsePrecompressedFormats(String precompressed, Boolean gzip, List<CompressedContentFormat> dft)
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
        return ret;
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
        if (value != null && value.length() > 0)
            return Integer.parseInt(value);
        return dft;
    }

    protected ServletContextHandler initContextHandler(ServletContext servletContext)
    {
        if (servletContext instanceof ServletContextHandler.ServletContextApi api)
            return api.getContext().getServletContextHandler();

        Context context = ContextHandler.getCurrentContext();
        if (context instanceof ContextHandler.ScopedContext scopedContext)
            return scopedContext.getContextHandler();

        throw new IllegalArgumentException("The servletContext " + servletContext + " " +
            servletContext.getClass().getName() + " is not " + ContextHandler.ScopedContext.class.getName());
    }

    protected boolean isPathInfoOnly()
    {
        return _isPathInfoOnly;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        String includedServletPath = (String)req.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH);
        boolean included = includedServletPath != null;
        String encodedPathInContext;
        if (included)
            encodedPathInContext = URIUtil.encodePath(getIncludedPathInContext(req, includedServletPath, isPathInfoOnly()));
        else if (isPathInfoOnly())
            encodedPathInContext = URIUtil.encodePath(req.getPathInfo());
        else if (req instanceof ServletApiRequest apiRequest)
            encodedPathInContext = Context.getPathInContext(req.getContextPath(), apiRequest.getServletContextRequest().getHttpURI().getCanonicalPath());
        else
            encodedPathInContext = Context.getPathInContext(req.getContextPath(), URIUtil.canonicalPath(req.getRequestURI()));

        if (LOG.isDebugEnabled())
            LOG.debug("doGet(req={}, resp={}) pathInContext={}, included={}", req, resp, encodedPathInContext, included);

        try
        {
            HttpContent content = _resourceService.getContent(encodedPathInContext, ServletContextRequest.getServletContextRequest(req));
            if (LOG.isDebugEnabled())
                LOG.debug("content = {}", content);

            if (content == null || Resources.missing(content.getResource()))
            {
                if (included)
                {
                    /* https://github.com/jakartaee/servlet/blob/6.0.0-RELEASE/spec/src/main/asciidoc/servlet-spec-body.adoc#93-the-include-method
                     * 9.3 - If the default servlet is the target of a RequestDispatch.include() and the requested
                     * resource does not exist, then the default servlet MUST throw FileNotFoundException.
                     * If the exception isn’t caught and handled, and the response
                     * hasn’t been committed, the status code MUST be set to 500.
                     */
                    throw new FileNotFoundException(encodedPathInContext);
                }

                // no content
                resp.sendError(404);
            }
            else
            {
                ServletCoreRequest coreRequest = new ServletCoreRequest(req);
                ServletCoreResponse coreResponse = new ServletCoreResponse(coreRequest, resp);

                if (coreResponse.isCommitted())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Response already committed for {}", coreRequest.getHttpURI());
                    return;
                }

                // Servlet Filters could be interacting with the Response already.
                if (coreResponse.isHttpServletResponseWrapped() ||
                    coreResponse.isWritingOrStreaming())
                {
                    content = new UnknownLengthHttpContent(content);
                }

                ServletContextResponse contextResponse = coreResponse.getServletContextResponse();
                if (contextResponse != null)
                {
                    String characterEncoding = contextResponse.getRawCharacterEncoding();
                    if (characterEncoding != null)
                        content = new ForcedCharacterEncodingHttpContent(content, characterEncoding);
                }

                // serve content
                try (Blocker.Callback callback = Blocker.callback())
                {
                    _resourceService.doGet(coreRequest, coreResponse, callback, content);
                    callback.block();
                }
                catch (Exception e)
                {
                    throw new ServletException(e);
                }
            }
        }
        catch (InvalidPathException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("InvalidPathException for pathInContext: {}", encodedPathInContext, e);
            if (included)
                throw new FileNotFoundException(encodedPathInContext);
            resp.setStatus(404);
        }
    }

    @Override
    protected void doHead(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("doHead(req={}, resp={}) (calling doGet())", req, resp);
        doGet(req, resp);
    }

    private Resource getResource(URI uri)
    {
        String uriPath = uri.getRawPath();
        Resource result = null;
        try
        {
            result = _contextHandler.getResource(uriPath);
        }
        catch (IOException x)
        {
            LOG.trace("IGNORED", x);
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Resource {}={}", uriPath, result);
        return result;
    }

    private static class ServletCoreRequest extends Request.Wrapper
    {
        // TODO fully implement this class and move it to the top level
        // TODO Some methods are directed to core that probably should be intercepted

        private final HttpServletRequest _servletRequest;
        private final HttpFields _httpFields;
        private final HttpURI _uri;

        ServletCoreRequest(HttpServletRequest request)
        {
            super(ServletContextRequest.getServletContextRequest(request));
            _servletRequest = request;

            HttpFields.Mutable fields = HttpFields.build();

            Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements())
            {
                String headerName = headerNames.nextElement();
                Enumeration<String> headerValues = request.getHeaders(headerName);
                while (headerValues.hasMoreElements())
                {
                    String headerValue = headerValues.nextElement();
                    fields.add(new HttpField(headerName, headerValue));
                }
            }

            _httpFields = fields.asImmutable();
            String includedServletPath = (String)request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH);
            boolean included = includedServletPath != null;
            if (request.getDispatcherType() == DispatcherType.REQUEST)
                _uri = getWrapped().getHttpURI();
            else if (included)
                _uri = Request.newHttpURIFrom(getWrapped(), URIUtil.encodePath(getIncludedPathInContext(request, includedServletPath, false)));
            else
                _uri = Request.newHttpURIFrom(getWrapped(), URIUtil.encodePath(URIUtil.addPaths(_servletRequest.getServletPath(), _servletRequest.getPathInfo())));
        }

        @Override
        public HttpFields getHeaders()
        {
            return _httpFields;
        }

        @Override
        public HttpURI getHttpURI()
        {
            return _uri;
        }

        @Override
        public String getId()
        {
            return _servletRequest.getRequestId();
        }

        @Override
        public String getMethod()
        {
            return _servletRequest.getMethod();
        }

        @Override
        public boolean isSecure()
        {
            return _servletRequest.isSecure();
        }

        @Override
        public boolean addErrorListener(Predicate<Throwable> onError)
        {
            return false;
        }

        @Override
        public void addHttpStreamWrapper(Function<HttpStream, HttpStream> wrapper)
        {
        }

        @Override
        public Object removeAttribute(String name)
        {
            Object value = _servletRequest.getAttribute(name);
            _servletRequest.removeAttribute(name);
            return value;
        }

        @Override
        public Object setAttribute(String name, Object attribute)
        {
            Object value = _servletRequest.getAttribute(name);
            _servletRequest.setAttribute(name, attribute);
            return value;
        }

        @Override
        public Object getAttribute(String name)
        {
            return _servletRequest.getAttribute(name);
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            Set<String> set = new HashSet<>();
            Enumeration<String> e = _servletRequest.getAttributeNames();
            while (e.hasMoreElements())
                set.add(e.nextElement());
            return set;
        }

        @Override
        public void clearAttributes()
        {
            Enumeration<String> e = _servletRequest.getAttributeNames();
            while (e.hasMoreElements())
                _servletRequest.removeAttribute(e.nextElement());
        }
    }

    private static class HttpServletResponseHttpFields implements HttpFields.Mutable
    {
        private final HttpServletResponse _response;

        private HttpServletResponseHttpFields(HttpServletResponse response)
        {
            _response = response;
        }

        @Override
        public ListIterator<HttpField> listIterator()
        {
            // The minimum requirement is to implement the listIterator, but it is inefficient.
            // Other methods are implemented for efficiency.
            final ListIterator<HttpField> list = _response.getHeaderNames().stream()
                .map(n -> new HttpField(n, _response.getHeader(n)))
                .collect(Collectors.toList())
                .listIterator();

            return new ListIterator<>()
            {
                HttpField _last;

                @Override
                public boolean hasNext()
                {
                    return list.hasNext();
                }

                @Override
                public HttpField next()
                {
                    return _last = list.next();
                }

                @Override
                public boolean hasPrevious()
                {
                    return list.hasPrevious();
                }

                @Override
                public HttpField previous()
                {
                    return _last = list.previous();
                }

                @Override
                public int nextIndex()
                {
                    return list.nextIndex();
                }

                @Override
                public int previousIndex()
                {
                    return list.previousIndex();
                }

                @Override
                public void remove()
                {
                    if (_last != null)
                    {
                        // This is not exactly the right semantic for repeated field names
                        list.remove();
                        _response.setHeader(_last.getName(), null);
                    }
                }

                @Override
                public void set(HttpField httpField)
                {
                    list.set(httpField);
                    _response.setHeader(httpField.getName(), httpField.getValue());
                }

                @Override
                public void add(HttpField httpField)
                {
                    list.add(httpField);
                    _response.addHeader(httpField.getName(), httpField.getValue());
                }
            };
        }

        @Override
        public Mutable add(String name, String value)
        {
            _response.addHeader(name, value);
            return this;
        }

        @Override
        public Mutable add(HttpHeader header, HttpHeaderValue value)
        {
            _response.addHeader(header.asString(), value.asString());
            return this;
        }

        @Override
        public Mutable add(HttpHeader header, String value)
        {
            _response.addHeader(header.asString(), value);
            return this;
        }

        @Override
        public Mutable add(HttpField field)
        {
            _response.addHeader(field.getName(), field.getValue());
            return this;
        }

        @Override
        public Mutable put(HttpField field)
        {
            _response.setHeader(field.getName(), field.getValue());
            return this;
        }

        @Override
        public Mutable put(String name, String value)
        {
            _response.setHeader(name, value);
            return this;
        }

        @Override
        public Mutable put(HttpHeader header, HttpHeaderValue value)
        {
            _response.setHeader(header.asString(), value.asString());
            return this;
        }

        @Override
        public Mutable put(HttpHeader header, String value)
        {
            _response.setHeader(header.asString(), value);
            return this;
        }

        @Override
        public Mutable put(String name, List<String> list)
        {
            Objects.requireNonNull(name);
            Objects.requireNonNull(list);
            boolean first = true;
            for (String s : list)
            {
                if (first)
                    _response.setHeader(name, s);
                else
                    _response.addHeader(name, s);
                first = false;
            }
            return this;
        }

        @Override
        public Mutable remove(HttpHeader header)
        {
            _response.setHeader(header.asString(), null);
            return this;
        }

        @Override
        public Mutable remove(EnumSet<HttpHeader> fields)
        {
            for (HttpHeader header : fields)
                remove(header);
            return this;
        }

        @Override
        public Mutable remove(String name)
        {
            _response.setHeader(name, null);
            return this;
        }
    }

    private static class ServletCoreResponse implements Response
    {
        // TODO fully implement this class and move it to the top level

        private final HttpServletResponse _response;
        private final ServletCoreRequest _coreRequest;
        private final Response _coreResponse;
        private final HttpFields.Mutable _httpFields;

        public ServletCoreResponse(ServletCoreRequest coreRequest, HttpServletResponse response)
        {
            _coreRequest = coreRequest;
            _response = response;
            _coreResponse = ServletContextResponse.getServletContextResponse(response);
            _httpFields = new HttpServletResponseHttpFields(response);
        }

        @Override
        public HttpFields.Mutable getHeaders()
        {
            return _httpFields;
        }

        public ServletContextResponse getServletContextResponse()
        {
            if (_response instanceof ServletApiResponse)
            {
                ServletApiResponse apiResponse = (ServletApiResponse)_response;
                return apiResponse.getResponse();
            }
            return null;
        }

        @Override
        public boolean isCommitted()
        {
            return _response.isCommitted();
        }

        /**
         * Test if the HttpServletResponse is wrapped by the webapp.
         *
         * @return true if wrapped.
         */
        public boolean isHttpServletResponseWrapped()
        {
            return (_response instanceof HttpServletResponseWrapper);
        }

        /**
         * Test if {@link HttpServletResponse#getOutputStream()} or
         * {@link HttpServletResponse#getWriter()} has been called already
         *
         * @return true if {@link HttpServletResponse} has started to write or stream content
         */
        public boolean isWritingOrStreaming()
        {
            ServletContextResponse servletContextResponse = Response.as(_coreResponse, ServletContextResponse.class);
            return servletContextResponse.isWritingOrStreaming();
        }

        public boolean isWriting()
        {
            ServletContextResponse servletContextResponse = Response.as(_coreResponse, ServletContextResponse.class);
            return servletContextResponse.isWriting();
        }

        @Override
        public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
        {
            try
            {
                if (BufferUtil.hasContent(byteBuffer))
                {
                    if (isWriting())
                    {
                        String characterEncoding = _response.getCharacterEncoding();
                        try (ByteBufferInputStream bbis = new ByteBufferInputStream(byteBuffer);
                             InputStreamReader reader = new InputStreamReader(bbis, characterEncoding))
                        {
                            IO.copy(reader, _response.getWriter());
                        }

                        if (last)
                            _response.getWriter().close();
                    }
                    else
                    {
                        BufferUtil.writeTo(byteBuffer, _response.getOutputStream());
                        if (last)
                            _response.getOutputStream().close();
                    }
                }

                callback.succeeded();
            }
            catch (Throwable t)
            {
                callback.failed(t);
            }
        }

        @Override
        public Request getRequest()
        {
            return _coreRequest;
        }

        @Override
        public int getStatus()
        {
            return _response.getStatus();
        }

        @Override
        public void setStatus(int code)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{}.setStatus({})", this.getClass().getSimpleName(), code);
            _response.setStatus(code);
        }

        @Override
        public Supplier<HttpFields> getTrailersSupplier()
        {
            return null;
        }

        @Override
        public void setTrailersSupplier(Supplier<HttpFields> trailers)
        {
        }

        @Override
        public boolean isCompletedSuccessfully()
        {
            return _coreResponse.isCompletedSuccessfully();
        }

        @Override
        public void reset()
        {
            _response.reset();
        }

        @Override
        public CompletableFuture<Void> writeInterim(int status, HttpFields headers)
        {
            return null;
        }
    }

    private class ServletResourceService extends ResourceService implements ResourceService.WelcomeFactory
    {
        private final ServletContextHandler _servletContextHandler;

        private ServletResourceService(ServletContextHandler servletContextHandler)
        {
            _servletContextHandler = servletContextHandler;
        }

        @Override
        public String getWelcomeTarget(Request coreRequest)
        {
            String[] welcomes = _servletContextHandler.getWelcomeFiles();
            if (welcomes == null)
                return null;

            HttpServletRequest request = getServletRequest(coreRequest);
            String pathInContext = Request.getPathInContext(coreRequest);
            String includedServletPath = (String)request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH);
            String requestTarget;
            if (includedServletPath != null)
                requestTarget = getIncludedPathInContext(request, includedServletPath, isPathInfoOnly());
            else
                requestTarget = isPathInfoOnly() ? request.getPathInfo() : pathInContext;

            String welcomeTarget = null;
            Resource base = _baseResource.resolve(requestTarget);
            if (Resources.isReadableDirectory(base))
            {
                for (String welcome : welcomes)
                {
                    String welcomeInContext = URIUtil.addPaths(pathInContext, welcome);

                    // If the welcome resource is a file, it has
                    // precedence over resources served by Servlets.
                    Resource welcomePath = base.resolve(welcome);
                    if (Resources.isReadableFile(welcomePath))
                        return welcomeInContext;

                    // Check whether a Servlet may serve the welcome resource.
                    if (_welcomeServletMode != WelcomeServletMode.NONE && welcomeTarget == null)
                    {
                        ServletHandler.MappedServlet entry = _servletContextHandler.getServletHandler().getMappedServlet(welcomeInContext);
                        // Is there a different Servlet that may serve the welcome resource?
                        if (entry != null && entry.getServletHolder().getServletInstance() != DefaultServlet.this)
                        {
                            if (_welcomeServletMode == WelcomeServletMode.MATCH || entry.getPathSpec().getDeclaration().equals(welcomeInContext))
                            {
                                welcomeTarget = welcomeInContext;
                                // Do not break the loop, because we want to try other welcome resources
                                // that may be files and take precedence over Servlet welcome resources.
                            }
                        }
                    }
                }
            }
            return welcomeTarget;
        }

        @Override
        protected void redirectWelcome(Request request, Response response, Callback callback, String welcomeTarget) throws IOException
        {
            HttpServletRequest servletRequest = getServletRequest(request);
            HttpServletResponse servletResponse = getServletResponse(response);

            boolean included = isIncluded(servletRequest);

            String servletPath = included ? (String)servletRequest.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH)
                : servletRequest.getServletPath();

            if (isPathInfoOnly())
                welcomeTarget = URIUtil.addPaths(servletPath, welcomeTarget);

            servletResponse.setContentLength(0);
            Response.sendRedirect(request, response, callback, welcomeTarget);
        }

        @Override
        protected void serveWelcome(Request request, Response response, Callback callback, String welcomeTarget) throws IOException
        {
            HttpServletRequest servletRequest = getServletRequest(request);
            HttpServletResponse servletResponse = getServletResponse(response);

            boolean included = isIncluded(servletRequest);

            RequestDispatcher dispatcher = servletRequest.getServletContext().getRequestDispatcher(welcomeTarget);
            if (dispatcher == null)
            {
                // We know that the welcome target exists and can be served.
                Response.writeError(request, response, callback, HttpStatus.INTERNAL_SERVER_ERROR_500);
                return;
            }

            try
            {
                if (included)
                {
                    dispatcher.include(servletRequest, servletResponse);
                }
                else
                {
                    servletRequest.setAttribute("org.eclipse.jetty.server.welcome", welcomeTarget);
                    dispatcher.forward(servletRequest, servletResponse);
                }
                callback.succeeded();
            }
            catch (ServletException e)
            {
                callback.failed(e);
            }
        }

        @Override
        protected void rehandleWelcome(Request request, Response response, Callback callback, String welcomeTarget) throws IOException
        {
            serveWelcome(request, response, callback, welcomeTarget);
        }

        @Override
        protected void writeHttpError(Request coreRequest, Response coreResponse, Callback callback, int statusCode)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("writeHttpError(coreRequest={}, coreResponse={}, callback={}, statusCode={})", coreRequest, coreResponse, callback, statusCode);
            writeHttpError(coreRequest, coreResponse, callback, statusCode, null, null);
        }

        @Override
        protected void writeHttpError(Request coreRequest, Response coreResponse, Callback callback, Throwable cause)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("writeHttpError(coreRequest={}, coreResponse={}, callback={}, cause={})", coreRequest, coreResponse, callback, cause, cause);

            int statusCode = HttpStatus.INTERNAL_SERVER_ERROR_500;
            String reason = null;
            if (cause instanceof HttpException httpException)
            {
                statusCode = httpException.getCode();
                reason = httpException.getReason();
            }
            writeHttpError(coreRequest, coreResponse, callback, statusCode, reason, cause);
        }

        @Override
        protected void writeHttpError(Request coreRequest, Response coreResponse, Callback callback, int statusCode, String reason, Throwable cause)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("writeHttpError(coreRequest={}, coreResponse={}, callback={}, statusCode={}, reason={}, cause={})", coreRequest, coreResponse, callback, statusCode, reason, cause, cause);
            HttpServletRequest request = getServletRequest(coreRequest);
            HttpServletResponse response = getServletResponse(coreResponse);
            try
            {
                // TODO: not sure if this is allowed here.
                if (cause != null)
                    request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, cause);
                response.sendError(statusCode, reason);
            }
            catch (IOException e)
            {
                // TODO: Need a better exception?
                throw new RuntimeException(e);
            }
            finally
            {
                callback.succeeded();
            }
        }

        @Override
        protected boolean passConditionalHeaders(Request request, Response response, HttpContent content, Callback callback) throws IOException
        {
            boolean included = isIncluded(getServletRequest(request));
            if (included)
                return false;
            return super.passConditionalHeaders(request, response, content, callback);
        }

        private HttpServletRequest getServletRequest(Request request)
        {
            // TODO, this unwrapping is fragile
            return ((ServletCoreRequest)request)._servletRequest;
        }

        private HttpServletResponse getServletResponse(Response response)
        {
            // TODO, this unwrapping is fragile
            return ((ServletCoreResponse)response)._response;
        }
    }

    private static String getIncludedPathInContext(HttpServletRequest request, String includedServletPath, boolean isPathInfoOnly)
    {
        String servletPath = isPathInfoOnly ? "/" : includedServletPath;
        String pathInfo = (String)request.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO);
        return URIUtil.addPaths(servletPath, pathInfo);
    }

    private static boolean isIncluded(HttpServletRequest request)
    {
        return request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI) != null;
    }

    /**
     * Wrap an existing HttpContent with one that takes has an unknown/unspecified length.
     */
    private static class UnknownLengthHttpContent extends HttpContent.Wrapper
    {
        public UnknownLengthHttpContent(HttpContent content)
        {
            super(content);
        }

        @Override
        public HttpField getContentLength()
        {
            return null;
        }

        @Override
        public long getContentLengthValue()
        {
            return -1;
        }
    }

    private static class ForcedCharacterEncodingHttpContent extends HttpContent.Wrapper
    {
        private final String characterEncoding;
        private final String contentType;

        public ForcedCharacterEncodingHttpContent(HttpContent content, String characterEncoding)
        {
            super(content);
            this.characterEncoding = characterEncoding;
            String mimeType = content.getContentTypeValue();
            int idx = mimeType.indexOf(";charset");
            if (idx >= 0)
                mimeType = mimeType.substring(0, idx);
            this.contentType = mimeType + ";charset=" + this.characterEncoding;
        }

        @Override
        public HttpField getContentType()
        {
            return new HttpField(HttpHeader.CONTENT_TYPE, this.contentType);
        }

        @Override
        public String getContentTypeValue()
        {
            return this.contentType;
        }

        @Override
        public String getCharacterEncoding()
        {
            return this.characterEncoding;
        }
    }

    /**
     * <p>The different modes a welcome resource may be served by a Servlet.</p>
     */
    private enum WelcomeServletMode
    {
        /**
         * <p>Welcome targets are not served by Servlets.</p>
         * <p>The welcome target must exist as a file on the filesystem.</p>
         */
        NONE,
        /**
         * <p>Welcome target that exist as files on the filesystem are
         * served, otherwise a matching Servlet may serve the welcome target.</p>
         */
        MATCH,
        /**
         * <p>Welcome target that exist as files on the filesystem are
         * served, otherwise an exact matching Servlet may serve the welcome target.</p>
         */
        EXACT
    }
}
