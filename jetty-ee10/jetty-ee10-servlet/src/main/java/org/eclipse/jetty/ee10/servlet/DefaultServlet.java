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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.CachingContentFactory;
import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Components;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.ResourceContentFactory;
import org.eclipse.jetty.server.ResourceService;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultServlet extends HttpServlet
{
    private static final Logger LOG = LoggerFactory.getLogger(DefaultServlet.class);
    private ServletResourceService _resourceService;
    private boolean _welcomeServlets = false;
    private boolean _welcomeExactServlets = false;

    @Override
    public void init() throws ServletException
    {
        ServletContextHandler servletContextHandler = initContextHandler(getServletContext());
        _resourceService = new ServletResourceService(servletContextHandler);
        _resourceService.setWelcomeFactory(_resourceService);

        // TODO lots of review needed of this initialization

        MimeTypes mimeTypes = new MimeTypes();
        CompressedContentFormat[] precompressedFormats = new CompressedContentFormat[0];
        _resourceService.setContentFactory(new CachingContentFactory(new ResourceContentFactory(servletContextHandler.getResourceBase(), mimeTypes, precompressedFormats)));

        if (servletContextHandler.getWelcomeFiles() == null)
            servletContextHandler.setWelcomeFiles(new String[]{"index.html", "index.jsp"});

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

        /*
        _useFileMappedBuffer = getInitBoolean("useFileMappedBuffer", _useFileMappedBuffer);

        _relativeResourceBase = getInitParameter("relativeResourceBase");

        String rb = getInitParameter("resourceBase");
        if (rb != null)
        {
            if (_relativeResourceBase != null)
                throw new UnavailableException("resourceBase & relativeResourceBase");
            try
            {
                _resourceBase = _contextHandler.newResource(rb);
            }
            catch (Exception e)
            {
                LOG.warn("Unable to create resourceBase from {}", rb, e);
                throw new UnavailableException(e.toString());
            }
        }

        String stylesheet = getInitParameter("stylesheet");
        try
        {
            if (stylesheet != null)
            {
                URI uri = Resource.toURI(stylesheet);
                _stylesheetMount = Resource.mountIfNeeded(uri);
                _stylesheet = Resource.newResource(uri);
                if (!_stylesheet.exists())
                {
                    LOG.warn("Stylesheet {} does not exist", stylesheet);
                    _stylesheet = null;
                }
            }
            if (_stylesheet == null)
            {
                _stylesheet = ResourceHandler.getDefaultStylesheet();
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

        String resourceCache = getInitParameter("resourceCache");
        int maxCacheSize = getInitInt("maxCacheSize", -2);
        int maxCachedFileSize = getInitInt("maxCachedFileSize", -2);
        int maxCachedFiles = getInitInt("maxCachedFiles", -2);
        if (resourceCache != null)
        {
            if (maxCacheSize != -1 || maxCachedFileSize != -2 || maxCachedFiles != -2)
                LOG.debug("ignoring resource cache configuration, using resourceCache attribute");
            if (_relativeResourceBase != null || _resourceBase != null)
                throw new UnavailableException("resourceCache specified with resource bases");
            _cache = (CachedContentFactory)_servletContext.getAttribute(resourceCache);
        }

        try
        {
            if (_cache == null && (maxCachedFiles != -2 || maxCacheSize != -2 || maxCachedFileSize != -2))
            {
                _cache = new CachedContentFactory(null, this, _mimeTypes, _useFileMappedBuffer, _resourceService.isEtags(), _resourceService.getPrecompressedFormats());
                if (maxCacheSize >= 0)
                    _cache.setMaxCacheSize(maxCacheSize);
                if (maxCachedFileSize >= -1)
                    _cache.setMaxCachedFileSize(maxCachedFileSize);
                if (maxCachedFiles >= -1)
                    _cache.setMaxCachedFiles(maxCachedFiles);
                _servletContext.setAttribute(resourceCache == null ? "resourceCache" : resourceCache, _cache);
            }
        }
        catch (Exception e)
        {
            LOG.warn("Unable to setup CachedContentFactory", e);
            throw new UnavailableException(e.toString());
        }

        HttpContent.ContentFactory contentFactory = _cache;
        if (contentFactory == null)
        {
            contentFactory = new ResourceContentFactory(this, _mimeTypes, _resourceService.getPrecompressedFormats());
            if (resourceCache != null)
                _servletContext.setAttribute(resourceCache, contentFactory);
        }
        _resourceService.setContentFactory(contentFactory);
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
            LOG.debug("resource base = {}", _resourceBase);
        */
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

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        HttpContent content = _resourceService.getContent(req.getServletPath(), resp.getBufferSize());
        if (content == null)
        {
            // no content
            resp.setStatus(404);
        }
        else
        {
            // serve content
            try (Blocker.Callback callback = Blocker.callback())
            {
                ServletCoreRequest coreRequest = new ServletCoreRequest(req);
                ServletCoreResponse coreResponse = new ServletCoreResponse(coreRequest, resp);
                _resourceService.doGet(coreRequest, coreResponse, callback, content);
                callback.block();
            }
            catch (Exception e)
            {
                throw new ServletException(e);
            }
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

        private final HttpServletRequest _request;
        private final Request _coreRequest;
        private final HttpFields _httpFields;

        ServletCoreRequest(HttpServletRequest request)
        {
            _request = request;
            _coreRequest = ServletContextRequest.getBaseRequest(request);

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
        public HttpURI getHttpURI()
        {
            return _coreRequest.getHttpURI();
        }

        @Override
        public String getPathInContext()
        {
            return URIUtil.addPaths(_request.getServletPath(), _request.getPathInfo());
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            _coreRequest.demand(demandCallback);
        }

        @Override
        public void fail(Throwable failure)
        {
            _coreRequest.fail(failure);
        }

        @Override
        public String getId()
        {
            return _request.getRequestId();
        }

        @Override
        public Components getComponents()
        {
            return _coreRequest.getComponents();
        }

        @Override
        public ConnectionMetaData getConnectionMetaData()
        {
            return _coreRequest.getConnectionMetaData();
        }

        @Override
        public String getMethod()
        {
            return _request.getMethod();
        }

        @Override
        public Context getContext()
        {
            return _coreRequest.getContext();
        }

        @Override
        public long getTimeStamp()
        {
            return _coreRequest.getTimeStamp();
        }

        @Override
        public boolean isSecure()
        {
            return _request.isSecure();
        }

        @Override
        public Content.Chunk read()
        {
            return _coreRequest.read();
        }

        @Override
        public void push(MetaData.Request request)
        {
            _coreRequest.push(request);
        }

        @Override
        public boolean addErrorListener(Predicate<Throwable> onError)
        {
            return false;
        }

        @Override
        public void addHttpStreamWrapper(Function<HttpStream, HttpStream.Wrapper> wrapper)
        {

        }

        @Override
        public Object removeAttribute(String name)
        {
            Object value = _request.getAttribute(name);
            _request.removeAttribute(name);
            return value;
        }

        @Override
        public Object setAttribute(String name, Object attribute)
        {
            Object value = _request.getAttribute(name);
            _request.setAttribute(name, attribute);
            return value;
        }

        @Override
        public Object getAttribute(String name)
        {
            return _request.getAttribute(name);
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            Set<String> set = new HashSet<>();
            Enumeration<String> e = _request.getAttributeNames();
            while (e.hasMoreElements())
                set.add(e.nextElement());
            return set;
        }

        @Override
        public void clearAttributes()
        {
            Enumeration<String> e = _request.getAttributeNames();
            while (e.hasMoreElements())
                _request.removeAttribute(e.nextElement());
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

        @Override
        public boolean isCommitted()
        {
            return _response.isCommitted();
        }

        @Override
        public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
        {
            try
            {
                if (BufferUtil.hasContent(byteBuffer))
                    BufferUtil.writeTo(byteBuffer, _response.getOutputStream());
                if (last)
                    _response.getOutputStream().close();
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
        public HttpFields.Mutable getOrCreateTrailers()
        {
            return null;
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
    }

    private class ServletResourceService extends ResourceService implements ResourceService.WelcomeFactory
    {
        private final ServletContextHandler _servletContextHandler;

        ServletResourceService(ServletContextHandler servletContextHandler)
        {
            _servletContextHandler = servletContextHandler;
        }

        @Override
        public String getWelcomeFile(String pathInContext) throws IOException
        {
            String[] welcomes = _servletContextHandler.getWelcomeFiles();

            if (welcomes == null)
                return null;

            // TODO this feels inefficient
            Resource base = _servletContextHandler.getResourceBase().resolve(pathInContext);
            String welcomeServlet = null;
            for (String welcome : welcomes)
            {
                Resource welcomePath = base.resolve(welcome);
                String welcomeInContext = URIUtil.addPaths(pathInContext, welcome);

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
            return welcomeServlet;
        }

        @Override
        protected boolean welcome(Request rq, Response rs, Callback callback) throws IOException
        {
            // TODO The contract of this method is very confused: it has a callback a return and throws?

            // TODO, this unwrapping is fragile
            HttpServletRequest request = ((ServletCoreRequest)rq)._request;
            HttpServletResponse response = ((ServletCoreResponse)rs)._response;
            String pathInContext = rq.getPathInContext();
            WelcomeFactory welcomeFactory = getWelcomeFactory();
            String welcome = welcomeFactory == null ? null : welcomeFactory.getWelcomeFile(pathInContext);
            boolean included = request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI) != null;

            if (welcome == null)
                return false;

            String servletPath = included ? (String)request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH)
                : request.getServletPath();

            if (isPathInfoOnly())
                welcome = URIUtil.addPaths(servletPath, welcome);

            if (LOG.isDebugEnabled())
                LOG.debug("welcome={}", welcome);

            ServletContext context = request.getServletContext();

            if (isRedirectWelcome() || context == null)
            {
                // Redirect to the index
                response.setContentLength(0);
                // TODO need URI util that handles param and query without reconstructing entire URI with scheme and authority
                HttpURI.Mutable uri = HttpURI.build(rq.getHttpURI());
                String parameter = uri.getParam();
                uri.path(URIUtil.addPaths(rq.getContext().getContextPath(), welcome));
                uri.param(parameter);
                response.sendRedirect(response.encodeRedirectURL(uri.getPathQuery()));
                callback.succeeded();
                return true;
            }

            RequestDispatcher dispatcher = context.getRequestDispatcher(URIUtil.encodePath(welcome));
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
                        request.setAttribute("org.eclipse.jetty.server.welcome", welcome);
                        dispatcher.forward(request, response);
                    }
                    callback.succeeded();
                    return true;
                }
                catch (ServletException e)
                {
                    callback.failed(e);
                    return true;
                }
            }

            return false;
        }

        @Override
        protected boolean passConditionalHeaders(Request request, Response response, HttpContent content, Callback callback) throws IOException
        {
            boolean included = ((ServletCoreRequest)request)._request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI) != null;
            if (included)
                return true;
            return super.passConditionalHeaders(request, response, content, callback);
        }
    }
}
