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

package org.eclipse.jetty.ee10.servlet;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.file.InvalidPathException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.CachingHttpContentFactory;
import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.FileMappedHttpContentFactory;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpContentWrapper;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.PreCompressedHttpContentFactory;
import org.eclipse.jetty.http.ResourceHttpContentFactory;
import org.eclipse.jetty.io.ByteBufferInputStream;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Components;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.ResourceService;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.TunnelSupport;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO restore javadoc
 */
public class DefaultServlet extends HttpServlet
{
    private static final Logger LOG = LoggerFactory.getLogger(DefaultServlet.class);
    private ServletResourceService _resourceService;
    private boolean _welcomeServlets = false;
    private boolean _welcomeExactServlets = false;

    private ResourceFactory.Closeable _resourceFactory;
    private Resource _baseResource;

    private boolean _isPathInfoOnly = false;

    public ResourceService getResourceService()
    {
        return _resourceService;
    }

    @Override
    public void init() throws ServletException
    {
        ServletContextHandler servletContextHandler = initContextHandler(getServletContext());
        _resourceService = new ServletResourceService(servletContextHandler);
        _resourceService.setWelcomeFactory(_resourceService);

        _baseResource = servletContextHandler.getBaseResource();
        String rb = getInitParameter("baseResource", "resourceBase");
        if (rb != null)
        {
            try
            {
                _resourceFactory = ResourceFactory.closeable();
                _baseResource = _resourceFactory.newResource(rb);
            }
            catch (Exception e)
            {
                LOG.warn("Unable to create baseResource from {}", rb, e);
                throw new UnavailableException(e.toString());
            }
        }

        List<CompressedContentFormat> precompressedFormats = parsePrecompressedFormats(getInitParameter("precompressed"),
            getInitBoolean("gzip"), _resourceService.getPrecompressedFormats());

        MimeTypes mimeTypes = servletContextHandler.getMimeTypes();
        HttpContent.Factory contentFactory = new ResourceHttpContentFactory(ResourceFactory.of(_baseResource), mimeTypes);
        contentFactory = new PreCompressedHttpContentFactory(contentFactory, precompressedFormats);
        CachingHttpContentFactory cached = getInitBoolean("useFileMappedBuffer", false)
            ? new CachingHttpContentFactory(new FileMappedHttpContentFactory(contentFactory))
            : new CachingHttpContentFactory(contentFactory);

        int maxCacheSize = getInitInt("maxCacheSize", -2);
        int maxCachedFileSize = getInitInt("maxCachedFileSize", -2);
        int maxCachedFiles = getInitInt("maxCachedFiles", -2);
        if (maxCachedFiles != -2 || maxCacheSize != -2 || maxCachedFileSize != -2)
        {
            if (maxCacheSize >= 0)
                cached.setMaxCacheSize(maxCacheSize);
            if (maxCachedFileSize >= -1)
                cached.setMaxCachedFileSize(maxCachedFileSize);
            if (maxCachedFiles >= -1)
                cached.setMaxCachedFiles(maxCachedFiles);
        }

        String resourceCache = getInitParameter("resourceCache");
        getServletContext().setAttribute(resourceCache == null ? "resourceCache" : resourceCache, cached);
        _resourceService.setContentFactory(cached);

        if (servletContextHandler.getWelcomeFiles() == null)
            servletContextHandler.setWelcomeFiles(new String[]{"index.html", "index.jsp"});

        _resourceService.setAcceptRanges(getInitBoolean("acceptRanges", _resourceService.isAcceptRanges()));
        _resourceService.setDirAllowed(getInitBoolean("dirAllowed", _resourceService.isDirAllowed()));
        _resourceService.setRedirectWelcome(getInitBoolean("redirectWelcome", _resourceService.isRedirectWelcome()));
        _resourceService.setPrecompressedFormats(precompressedFormats);
        _resourceService.setEtags(getInitBoolean("etags", _resourceService.isEtags()));

        _isPathInfoOnly = getInitBoolean("pathInfoOnly", _isPathInfoOnly);

        if ("exact".equals(getInitParameter("welcomeServlets")))
        {
            _welcomeExactServlets = true;
            _welcomeServlets = false;
        }
        else
            _welcomeServlets = getInitBoolean("welcomeServlets", _welcomeServlets);

        // Use the servers default stylesheet unless there is one explicitly set by an init param.
        _resourceService.setStylesheet(servletContextHandler.getServer().getDefaultStyleSheet());
        String stylesheetParam = getInitParameter("stylesheet");
        if (stylesheetParam != null)
        {
            try
            {
                Resource stylesheet = _resourceFactory.newResource(stylesheetParam);
                if (stylesheet.exists())
                {
                    _resourceService.setStylesheet(stylesheet);
                }
                else
                {
                    LOG.warn("Stylesheet {} does not exist", stylesheetParam);
                }
            }
            catch (Exception e)
            {
                if (LOG.isDebugEnabled())
                    LOG.warn("Unable to use stylesheet: {}", stylesheetParam, e);
                else
                    LOG.warn("Unable to use stylesheet: {} - {}", stylesheetParam, e.toString());
            }
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

        // TODO: remove? _servletHandler = _contextHandler.getChildHandlerByClass(ServletHandler.class);

        if (LOG.isDebugEnabled())
            LOG.debug("base resource = {}", _baseResource);
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

    @Override
    public void destroy()
    {
        super.destroy();
        IO.close(_resourceFactory);
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
        if (value == null)
            value = getInitParameter(name);
        if (value != null && value.length() > 0)
            return Integer.parseInt(value);
        return dft;
    }

    protected ServletContextHandler initContextHandler(ServletContext servletContext)
    {
        if (servletContext instanceof ServletContextHandler.ServletContextApi api)
            return api.getContext().getServletContextHandler();

        ContextHandler.Context context = ContextHandler.getCurrentContext();
        if (context != null)
            return context.getContextHandler();

        throw new IllegalArgumentException("The servletContext " + servletContext + " " +
            servletContext.getClass().getName() + " is not " + ContextHandler.Context.class.getName());
    }

    protected boolean isPathInfoOnly()
    {
        return _isPathInfoOnly;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        String pathInContext = isPathInfoOnly() ? req.getPathInfo() : URIUtil.addPaths(req.getServletPath(), req.getPathInfo());
        boolean included = req.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI) != null;
        try
        {
            HttpContent content = _resourceService.getContent(pathInContext, ServletContextRequest.getServletContextRequest(req));
            if (content == null || !content.getResource().exists())
            {
                if (included)
                {
                    /* https://github.com/jakartaee/servlet/blob/6.0.0-RELEASE/spec/src/main/asciidoc/servlet-spec-body.adoc#93-the-include-method
                     * 9.3 - If the default servlet is the target of a RequestDispatch.include() and the requested
                     * resource does not exist, then the default servlet MUST throw FileNotFoundException.
                     * If the exception isn’t caught and handled, and the response
                     * hasn’t been committed, the status code MUST be set to 500.
                     */
                    throw new FileNotFoundException(pathInContext);
                }

                // no content
                resp.setStatus(404);
            }
            else
            {
                ServletCoreRequest coreRequest = new ServletCoreRequest(req);
                ServletCoreResponse coreResponse = new ServletCoreResponse(coreRequest, resp);

                if (coreResponse.isCommitted())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Response already committed for {}", coreRequest._request.getHttpURI());
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
                LOG.debug("InvalidPathException for pathInContext: {}", pathInContext, e);
            if (included)
                throw new FileNotFoundException(pathInContext);
            resp.setStatus(404);
        }
    }

    @Override
    protected void doHead(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        doGet(req, resp);
    }

    private static class ServletCoreRequest implements Request
    {
        // TODO fully implement this class and move it to the top level
        // TODO Some methods are directed to core that probably should be intercepted

        private final HttpServletRequest _servletRequest;
        private final Request _request;
        private final HttpFields _httpFields;

        ServletCoreRequest(HttpServletRequest request)
        {
            _servletRequest = request;
            _request = ServletContextRequest.getServletContextRequest(request);

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
        }

        @Override
        public HttpFields getHeaders()
        {
            return _httpFields;
        }

        @Override
        public HttpFields getTrailers()
        {
            return _request.getTrailers();
        }

        @Override
        public HttpURI getHttpURI()
        {
            return _request.getHttpURI();
        }

        @Override
        public String getPathInContext()
        {
            return URIUtil.addPaths(_servletRequest.getServletPath(), _servletRequest.getPathInfo());
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            _request.demand(demandCallback);
        }

        @Override
        public void fail(Throwable failure)
        {
            _request.fail(failure);
        }

        @Override
        public String getId()
        {
            return _servletRequest.getRequestId();
        }

        @Override
        public Components getComponents()
        {
            return _request.getComponents();
        }

        @Override
        public ConnectionMetaData getConnectionMetaData()
        {
            return _request.getConnectionMetaData();
        }

        @Override
        public String getMethod()
        {
            return _servletRequest.getMethod();
        }

        @Override
        public Context getContext()
        {
            return _request.getContext();
        }

        @Override
        public long getTimeStamp()
        {
            return _request.getTimeStamp();
        }

        @Override
        public boolean isSecure()
        {
            return _servletRequest.isSecure();
        }

        @Override
        public Content.Chunk read()
        {
            return _request.read();
        }

        @Override
        public boolean isPushSupported()
        {
            return _request.isPushSupported();
        }

        @Override
        public void push(MetaData.Request request)
        {
            this._request.push(request);
        }

        @Override
        public boolean addErrorListener(Predicate<Throwable> onError)
        {
            return false;
        }

        @Override
        public TunnelSupport getTunnelSupport()
        {
            return _request.getTunnelSupport();
        }

        @Override
        public void addHttpStreamWrapper(Function<HttpStream, HttpStream.Wrapper> wrapper)
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
            _coreResponse = ServletContextResponse.getBaseResponse(response);
            _httpFields = new HttpServletResponseHttpFields(response);
        }

        @Override
        public HttpFields.Mutable getHeaders()
        {
            return _httpFields;
        }

        public ServletContextResponse getServletContextResponse()
        {
            if (_response instanceof ServletContextResponse.ServletApiResponse)
            {
                ServletContextResponse.ServletApiResponse apiResponse = (ServletContextResponse.ServletApiResponse)_response;
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

        public boolean isStreaming()
        {
            ServletContextResponse servletContextResponse = Response.as(_coreResponse, ServletContextResponse.class);
            return servletContextResponse.isStreaming();
        }

        @Override
        public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
        {
            try
            {
                if (BufferUtil.hasContent(byteBuffer))
                {
                    if (isStreaming())
                    {
                        BufferUtil.writeTo(byteBuffer, _response.getOutputStream());
                        if (last)
                            _response.getOutputStream().close();
                    }
                    else
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

        ServletResourceService(ServletContextHandler servletContextHandler)
        {
            _servletContextHandler = servletContextHandler;
        }

        @Override
        public String getWelcomeTarget(Request coreRequest) throws IOException
        {
            String[] welcomes = _servletContextHandler.getWelcomeFiles();

            if (welcomes == null)
                return null;

            HttpServletRequest request = getServletRequest(coreRequest);

            boolean included = request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI) != null;

            if (included)
            {
                // Servlet 9.3 - don't process welcome target from INCLUDE dispatch
                return null;
            }

            String requestTarget = isPathInfoOnly() ? request.getPathInfo() : coreRequest.getPathInContext();

            String welcomeServlet = null;
            Resource base = _baseResource.resolve(requestTarget);
            if (base != null && base.exists())
            {
                for (String welcome : welcomes)
                {
                    Resource welcomePath = base.resolve(welcome);
                    String welcomeInContext = URIUtil.addPaths(coreRequest.getPathInContext(), welcome);

                    if (welcomePath != null && welcomePath.exists())
                        return welcomeInContext;

                    if ((_welcomeServlets || _welcomeExactServlets) && welcomeServlet == null)
                    {
                        ServletHandler.MappedServlet entry = _servletContextHandler.getServletHandler().getMappedServlet(welcomeInContext);
                        if (entry != null && entry.getServletHolder().getServletInstance() != DefaultServlet.this &&
                            (_welcomeServlets || (_welcomeExactServlets && entry.getPathSpec().getDeclaration().equals(welcomeInContext))))
                            welcomeServlet = welcomeInContext;
                    }
                }
            }
            return welcomeServlet;
        }

        @Override
        protected void welcomeActionProcess(Request coreRequest, Response coreResponse, Callback callback, WelcomeAction welcomeAction) throws IOException
        {
            HttpServletRequest request = getServletRequest(coreRequest);
            HttpServletResponse response = getServletResponse(coreResponse);
            ServletContext context = request.getServletContext();
            boolean included = request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI) != null;

            String welcome = welcomeAction.target();

            switch (welcomeAction.type())
            {
                case REDIRECT ->
                {
                    if (isRedirectWelcome() || context == null)
                    {
                        String servletPath = included ? (String)request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH)
                            : request.getServletPath();

                        if (isPathInfoOnly())
                            welcome = URIUtil.addPaths(servletPath, welcome);

                        response.setContentLength(0);
                        response.sendRedirect(welcome); // Call API (might be overridden)
                        callback.succeeded();
                    }
                }
                case SERVE ->
                {
                    RequestDispatcher dispatcher = context.getRequestDispatcher(welcome);
                    if (dispatcher != null)
                    {
                        // Forward to the index
                        try
                        {
                            if (included)
                            {
                                dispatcher.include(request, response);
                            }
                            else
                            {
                                request.setAttribute("org.eclipse.jetty.server.welcome", welcomeAction.target());
                                dispatcher.forward(request, response);
                            }
                            callback.succeeded();
                        }
                        catch (ServletException e)
                        {
                            callback.failed(e);
                        }
                    }
                }
            }
        }

        @Override
        protected void writeHttpError(Request coreRequest, Response coreResponse, Callback callback, int statusCode)
        {
            writeHttpError(coreRequest, coreResponse, callback, statusCode, null, null);
        }

        @Override
        protected void writeHttpError(Request coreRequest, Response coreResponse, Callback callback, Throwable cause)
        {
            int statusCode = HttpStatus.INTERNAL_SERVER_ERROR_500;
            String reason = null;
            if (cause instanceof BadMessageException badMessageException)
            {
                statusCode = badMessageException.getCode();
                reason = badMessageException.getReason();
            }
            writeHttpError(coreRequest, coreResponse, callback, statusCode, reason, cause);
        }

        @Override
        protected void writeHttpError(Request coreRequest, Response coreResponse, Callback callback, int statusCode, String reason, Throwable cause)
        {
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
            boolean included = getServletRequest(request).getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI) != null;
            if (included)
                return true;
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

    /**
     * Wrap an existing HttpContent with one that takes has an unknown/unspecified length.
     */
    private static class UnknownLengthHttpContent extends HttpContentWrapper
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
            return ResourceService.NO_CONTENT_LENGTH;
        }
    }

    private static class ForcedCharacterEncodingHttpContent extends HttpContentWrapper
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
}
