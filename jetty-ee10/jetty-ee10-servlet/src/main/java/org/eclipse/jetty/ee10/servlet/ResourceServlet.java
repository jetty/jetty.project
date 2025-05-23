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
import java.nio.file.InvalidPathException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.StringTokenizer;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.content.FileMappingHttpContentFactory;
import org.eclipse.jetty.http.content.HttpContent;
import org.eclipse.jetty.http.content.PreCompressedHttpContentFactory;
import org.eclipse.jetty.http.content.ResourceHttpContentFactory;
import org.eclipse.jetty.http.content.ValidatingCachingHttpContentFactory;
import org.eclipse.jetty.http.content.VirtualHttpContentFactory;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.AliasCheck;
import org.eclipse.jetty.server.AllowedResourceAliasChecker;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.ResourceService;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SymlinkAllowedResourceAliasChecker;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A Servlet that handles static resources.</p>
 * <p>The following init parameters are supported:</p>
 * <dl>
 *   <dt>acceptRanges</dt>
 *   <dd>
 *     Use {@code true} to accept range requests, defaults to {@code true}.
 *   </dd>
 *   <dt>baseResource</dt>
 *   <dd>
 *     The root directory to look for static resources. Defaults to the context's baseResource. Relative URI
 *     are {@link Resource#resolve(String) resolved} against the context's {@link ServletContextHandler#getBaseResource()}
 *     base resource, all other values are resolved using {@link ServletContextHandler#newResource(String)}.
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
 *   <dt>installAllowedResourceAliasChecker</dt>
 *   <dd>
 *     <em>Deprecated</em> use {@code allowAliases} instead.
 *   </dd>
 *   <dt>allowAliases</dt>
 *   <dd>
 *     Allow resource aliases via the {@link AllowedResourceAliasChecker}
 *     on the context (if one does not already exist) for this baseResource.
 *     This is especially useful if you have a FileSystem that is not
 *     case sensitive. (Such as on Windows with FAT or NTFS)
 *     Defaults to {@code true}.
 *   </dd>
 *   <dt>allowSymlinks</dt>
 *   <dd>
 *     Allow resources that are symlinks pointing to other locations via
 *     the {@link SymlinkAllowedResourceAliasChecker} on the context (if one
 *     does not already exist) for this baseResource.
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
 *     Use {@code true} to use only the pathInfo portion of a PATH (aka prefix) match
 *     as obtained from {@link HttpServletRequest#getPathInfo()}.
 *     Defaults to {@code true}.
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
 *   <dt>resourceBase</dt>
 *   <dd>
 *     <em>Deprecated</em> use {@code baseResource} instead.
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
public class ResourceServlet extends HttpServlet
{
    private static final Logger LOG = LoggerFactory.getLogger(ResourceServlet.class);

    private ServletResourceService _resourceService;
    private WelcomeServletMode _welcomeServletMode;
    private boolean _pathInfoOnly;

    public ResourceService getResourceService()
    {
        return _resourceService;
    }

    @Override
    public void init() throws ServletException
    {
        ServletContextHandler contextHandler = initContextHandler(getServletContext());
        _resourceService = new ServletResourceService(contextHandler);
        _resourceService.setWelcomeFactory(_resourceService);
        Resource baseResource = contextHandler.getBaseResource();

        String rb = getInitParameter("baseResource", "resourceBase");
        if (rb != null)
        {
            try
            {
                baseResource = URIUtil.isRelative(rb) ? baseResource.resolve(rb) :  contextHandler.newResource(rb);
                if (baseResource.isAlias())
                    baseResource = contextHandler.newResource(baseResource.getRealURI());
            }
            catch (Exception e)
            {
                LOG.warn("Unable to create baseResource from {}", rb, e);
                throw new UnavailableException(e.toString());
            }
        }
        if (baseResource != null && !(baseResource.isDirectory() && baseResource.isReadable()))
            LOG.warn("baseResource {} is not a readable directory", baseResource);

        if (getInitBoolean("allowAliases", true, "installAllowedResourceAliasChecker"))
        {
            // Add a new aliasCheck to the ContextHandler if one does not exist for this baseResource.
            boolean addAliasCheck = true;
            for (AliasCheck aliasCheck : contextHandler.getAliasChecks())
            {
                if (aliasCheck instanceof AllowedResourceAliasChecker allowedResourceAliasChecker &&
                    Objects.equals(baseResource, allowedResourceAliasChecker.getBaseResource()))
                {
                    addAliasCheck = false;
                    break;
                }
            }
            if (addAliasCheck)
                contextHandler.addAliasCheck(new AllowedResourceAliasChecker(contextHandler, baseResource));
        }

        if (getInitBoolean("allowSymlinks", false))
        {
            // Add a new aliasCheck to the ContextHandler if one does not exist for this baseResource.
            boolean addAliasCheck = true;
            for (AliasCheck aliasCheck : contextHandler.getAliasChecks())
            {
                if (aliasCheck instanceof SymlinkAllowedResourceAliasChecker aliasChecker &&
                    Objects.equals(baseResource, aliasChecker.getBaseResource()))
                {
                    addAliasCheck = false;
                    break;
                }
            }
            if (addAliasCheck)
                contextHandler.addAliasCheck(new SymlinkAllowedResourceAliasChecker(contextHandler, baseResource));
        }

        List<CompressedContentFormat> precompressedFormats = parsePrecompressedFormats(getInitParameter("precompressed"),
            getInitBoolean("gzip"), _resourceService.getPrecompressedFormats());

        // Try to get factory from ServletContext attribute.
        HttpContent.Factory contentFactory = (HttpContent.Factory)getServletContext().getAttribute(HttpContent.Factory.class.getName());
        if (contentFactory == null)
        {
            MimeTypes mimeTypes = contextHandler.getMimeTypes();
            contentFactory = new ResourceHttpContentFactory(baseResource, mimeTypes);

            // Use the servers default stylesheet unless there is one explicitly set by an init param.
            Resource styleSheet = contextHandler.getServer().getDefaultStyleSheet();
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
                ByteBufferPool bufferPool = getByteBufferPool(contextHandler);
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

        if (contextHandler.getWelcomeFiles() == null)
            contextHandler.setWelcomeFiles(new String[]{"index.html", "index.jsp"});

        _resourceService.setAcceptRanges(getInitBoolean("acceptRanges", _resourceService.isAcceptRanges()));
        _resourceService.setDirAllowed(getInitBoolean("dirAllowed", _resourceService.isDirAllowed()));
        boolean redirectWelcome = getInitBoolean("redirectWelcome", false);
        _resourceService.setWelcomeMode(redirectWelcome ? ResourceService.WelcomeMode.REDIRECT : ResourceService.WelcomeMode.SERVE);
        _resourceService.setPrecompressedFormats(precompressedFormats);
        _resourceService.setEtags(getInitBoolean("etags", _resourceService.isEtags()));

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

        _pathInfoOnly = getInitBoolean("pathInfoOnly", true);

        if (LOG.isDebugEnabled())
        {
            LOG.debug("  .baseResource = {}", baseResource);
            LOG.debug("  .resourceService = {}", _resourceService);
            LOG.debug("  .welcomeServletMode = {}", _welcomeServletMode);
        }
    }

    private static ByteBufferPool getByteBufferPool(ContextHandler contextHandler)
    {
        if (contextHandler == null)
            return ByteBufferPool.NON_POOLING;
        Server server = contextHandler.getServer();
        if (server == null)
            return ByteBufferPool.NON_POOLING;
        return server.getByteBufferPool();
    }

    private String getInitParameter(String name, String... deprecated)
    {
        String value = getInitParameter(name);
        if (value != null)
            return value;

        for (String d : deprecated)
        {
            value = getInitParameter(d);
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
        if (value == null || value.isEmpty())
            return null;
        return (value.startsWith("t") ||
            value.startsWith("T") ||
            value.startsWith("y") ||
            value.startsWith("Y") ||
            value.startsWith("1"));
    }

    private Boolean getInitBoolean(String name, boolean dft, String... deprecated)
    {
        String value = getInitParameter(name, deprecated);
        if (value == null || value.isEmpty())
            return dft;
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
        if (value != null && !value.isEmpty())
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

    @Override
    protected void doGet(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws ServletException, IOException
    {
        boolean included = httpServletRequest.getDispatcherType() == DispatcherType.INCLUDE;
        String encodedPathInContext = getEncodedPathInContext(httpServletRequest, included);

        if (LOG.isDebugEnabled())
            LOG.debug("doGet(hsReq={}, hsResp={}) pathInContext={}, included={}", httpServletRequest, httpServletResponse, encodedPathInContext, included);

        try
        {
            HttpContent content = _resourceService.getContent(encodedPathInContext, ServletContextRequest.getServletContextRequest(httpServletRequest));
            if (LOG.isDebugEnabled())
                LOG.debug("content = {}", content);

            if (content == null || Resources.missing(content.getResource()))
            {
                doNotFound(httpServletRequest, httpServletResponse, encodedPathInContext);
            }
            else
            {
                // lookup the core request and response as wrapped by the ServletContextHandler
                ServletContextRequest servletContextRequest = ServletContextRequest.getServletContextRequest(httpServletRequest);
                ServletContextResponse servletContextResponse = servletContextRequest.getServletContextResponse();
                ServletChannel servletChannel = servletContextRequest.getServletChannel();

                // If the servlet request has not been wrapped,
                // we can use the core request directly,
                // otherwise wrap the servlet request as a core request
                Request coreRequest = httpServletRequest instanceof ServletApiRequest
                    ? servletChannel.getRequest()
                    : ServletCoreRequest.wrap(httpServletRequest);

                // If the servlet response has been wrapped and has been written to,
                // then the servlet response must be wrapped as a core response
                // otherwise we can use the core response directly.
                boolean writingOrStreaming = servletContextResponse.isWritingOrStreaming();
                boolean useServletResponse = !(httpServletResponse instanceof ServletApiResponse) || writingOrStreaming;
                Response coreResponse = useServletResponse
                    ? new ServletCoreResponse(coreRequest, httpServletResponse, included)
                    : servletChannel.getResponse();

                // If the core response is already committed then do nothing more
                if (coreResponse.isCommitted())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Response already committed for {}", coreRequest.getHttpURI());
                    return;
                }

                // Get the content length before we may wrap the content
                long contentLength = content.getContentLengthValue();

                // If the response is already written, then don't set the content-length.
                if (writingOrStreaming)
                    content = new UnknownLengthHttpContent(content);

                // The character encoding may be forced
                String characterEncoding = servletContextResponse.getRawCharacterEncoding();
                if (characterEncoding != null)
                    content = new ForcedCharacterEncodingHttpContent(content, characterEncoding);

                // If async is supported and the unwrapped content is larger than an output buffer
                if (httpServletRequest.isAsyncSupported() &&
                    (contentLength < 0 || contentLength > coreRequest.getConnectionMetaData().getHttpConfiguration().getOutputBufferSize()))
                {
                    // send the content asynchronously
                    AsyncContext asyncContext = httpServletRequest.isAsyncStarted() ? httpServletRequest.getAsyncContext() : httpServletRequest.startAsync();
                    Callback callback = new AsyncContextCallback(asyncContext, httpServletResponse);
                    _resourceService.doGet(coreRequest, coreResponse, callback, content);
                }
                else
                {
                    // send the content blocking
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
        }
        catch (InvalidPathException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("InvalidPathException for pathInContext: {}", encodedPathInContext, e);
            if (included)
                throw new FileNotFoundException(encodedPathInContext);
            httpServletResponse.setStatus(404);
        }
    }

    protected String getEncodedPathInContext(HttpServletRequest request, boolean included)
    {
        HttpServletMapping mapping = request.getHttpServletMapping();
        if (included)
        {
            if (request.getAttribute(Dispatcher.INCLUDE_MAPPING) instanceof HttpServletMapping httpServletMapping)
            {
                mapping = httpServletMapping;
            }
            else
            {
                // must be an include of a named dispatcher.  Just use the whole URI
                return URIUtil.encodePath(URIUtil.addPaths(request.getServletPath(), request.getPathInfo()));
            }
        }

        return switch (mapping.getMappingMatch())
        {
            case CONTEXT_ROOT -> "/";
            case DEFAULT, EXTENSION, EXACT ->
            {
                if (included)
                    yield URIUtil.encodePath((String)request.getAttribute(Dispatcher.INCLUDE_SERVLET_PATH));
                else if (request instanceof ServletApiRequest apiRequest)
                    // Strip the context path from the canonically encoded path, so no need to re-encode (and mess up %2F etc.)
                    yield Context.getPathInContext(request.getContextPath(), apiRequest.getRequest().getHttpURI().getCanonicalPath());
                else
                    yield URIUtil.encodePath(request.getServletPath());
            }
            case PATH ->
            {
                if (_pathInfoOnly)
                {
                    if (included)
                        yield URIUtil.encodePath((String)request.getAttribute(Dispatcher.INCLUDE_PATH_INFO));
                    else
                        yield URIUtil.encodePath(request.getPathInfo());
                }
                else
                {
                    if (included)
                        yield URIUtil.encodePath(URIUtil.addPaths((String)request.getAttribute(Dispatcher.INCLUDE_SERVLET_PATH), (String)request.getAttribute(Dispatcher.INCLUDE_PATH_INFO)));
                    else if (request instanceof ServletApiRequest apiRequest)
                        // Strip the context path from the canonically encoded path, so no need to re-encode (and mess up %2F etc.)
                        yield Context.getPathInContext(request.getContextPath(), apiRequest.getRequest().getHttpURI().getCanonicalPath());
                    else
                        yield URIUtil.encodePath(URIUtil.addPaths(request.getServletPath(), request.getPathInfo()));
                }
            }
        };
    }

    @Override
    protected void doHead(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("doHead(req={}, resp={}) (calling doGet())", req, resp);
        doGet(req, resp);
    }

    @Override
    protected void doTrace(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        // Always return 405: Method Not Allowed for DefaultServlet
        resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        // override to eliminate TRACE that the default HttpServlet impl adds
        resp.setHeader("Allow", "GET, HEAD, OPTIONS");
    }

    protected void doNotFound(HttpServletRequest request, HttpServletResponse response, String encodedPathInContext) throws IOException
    {
        if (request.getDispatcherType() == DispatcherType.INCLUDE)
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
        response.sendError(404);
    }

    private class ServletResourceService extends ResourceService implements ResourceService.WelcomeFactory
    {
        private final ServletContextHandler _servletContextHandler;

        private ServletResourceService(ServletContextHandler servletContextHandler)
        {
            _servletContextHandler = servletContextHandler;
        }

        @Override
        public String getWelcomeTarget(HttpContent content, Request coreRequest)
        {
            String[] welcomes = _servletContextHandler.getWelcomeFiles();
            if (welcomes == null)
                return null;
            String pathInContext = Request.getPathInContext(coreRequest);
            String welcomeTarget = null;
            Resource base = content.getResource();
            if (Resources.isReadableDirectory(base))
            {
                for (String welcome : welcomes)
                {
                    String welcomeInContext = URIUtil.addPaths(pathInContext, welcome);

                    // If the welcome resource is a file, it has
                    // precedence over resources served by Servlets.
                    Resource welcomePath = content.getResource().resolve(welcome);
                    if (Resources.isReadableFile(welcomePath))
                        return welcomeInContext;

                    // Check whether a Servlet may serve the welcome resource.
                    if (_welcomeServletMode != WelcomeServletMode.NONE && welcomeTarget == null)
                    {
                        ServletHandler.MappedServlet entry = _servletContextHandler.getServletHandler().getMappedServlet(welcomeInContext);
                        // Is there a different Servlet that may serve the welcome resource?
                        if (entry != null && entry.getServletHolder().getServletInstance() != ResourceServlet.this)
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
                if (isIncluded(request))
                    return;
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
            ServletCoreRequest servletCoreRequest = Request.asInContext(request, ServletCoreRequest.class);
            if (servletCoreRequest != null)
                return servletCoreRequest.getServletRequest();

            ServletContextRequest servletContextRequest = Request.as(request, ServletContextRequest.class);
            if (servletContextRequest != null)
                return servletContextRequest.getServletApiRequest();

            throw new IllegalStateException("instanceof " + request.getClass());
        }

        private HttpServletResponse getServletResponse(Response response)
        {
            ServletCoreResponse servletCoreResponse = Response.asInContext(response, ServletCoreResponse.class);
            if (servletCoreResponse != null)
                return servletCoreResponse.getServletResponse();

            ServletContextResponse servletContextResponse = Response.asInContext(response, ServletContextResponse.class);
            if (servletContextResponse != null)
                return servletContextResponse.getServletApiResponse();

            throw new IllegalStateException("instanceof " + response.getClass());
        }
    }

    static String getIncludedPathInContext(HttpServletRequest request, String includedServletPath)
    {
        String pathInfo = (String)request.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO);
        return URIUtil.addPaths(includedServletPath, pathInfo);
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
            super(Objects.requireNonNull(content));
            this.characterEncoding = characterEncoding;
            if (content.getContentTypeValue() == null || content.getResource().isDirectory())
            {
                this.contentType = null;
            }
            else
            {
                String mimeType = content.getContentTypeValue();
                int idx = mimeType.indexOf(";charset");
                if (idx >= 0)
                    mimeType = mimeType.substring(0, idx);
                this.contentType = mimeType + ";charset=" + characterEncoding;
            }
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

    private static class AsyncContextCallback implements Callback
    {
        private final AsyncContext _asyncContext;
        private final HttpServletResponse _response;

        private AsyncContextCallback(AsyncContext asyncContext, HttpServletResponse response)
        {
            _asyncContext = asyncContext;
            _response = response;
        }

        @Override
        public void succeeded()
        {
            _asyncContext.complete();
        }

        @Override
        public void failed(Throwable x)
        {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("AsyncContextCallback failed {}", _asyncContext, x);
                // It is known that this callback is only failed if the response is already committed,
                // thus we can only abort the response here.
                _response.sendError(-1);
            }
            catch (IOException e)
            {
                ExceptionUtil.addSuppressedIfNotAssociated(x, e);
            }
            finally
            {
                _asyncContext.complete();
            }
            if (LOG.isDebugEnabled())
                LOG.debug("Async get failed", x);
        }
    }
}
