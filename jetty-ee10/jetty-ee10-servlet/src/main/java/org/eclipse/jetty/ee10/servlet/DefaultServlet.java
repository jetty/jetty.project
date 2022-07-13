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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.stream.Collectors;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
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
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.ResourceContentFactory;
import org.eclipse.jetty.server.ResourceService;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultServlet extends HttpServlet
{
    private ResourceService _resourceService;

    @Override
    public void init(ServletConfig config) throws ServletException
    {
        ContextHandler contextHandler = initContextHandler(config.getServletContext());

        _resourceService = new ServletResourceService();
        MimeTypes mimeTypes = new MimeTypes();
        CompressedContentFormat[] precompressedFormats = new CompressedContentFormat[0];
        _resourceService.setContentFactory(new CachingContentFactory(new ResourceContentFactory(contextHandler.getResourceBase(), mimeTypes, precompressedFormats)));

        // TODO init other settings
    }

    protected ContextHandler initContextHandler(ServletContext servletContext)
    {
        ContextHandler.Context context = ContextHandler.getCurrentContext();
        if (context == null)
        {
            if (servletContext instanceof ServletContextHandler.ServletContextApi api)
                return api.getContext().getServletContextHandler();

            throw new IllegalArgumentException("The servletContext " + servletContext + " " +
                servletContext.getClass().getName() + " is not " + ContextHandler.Context.class.getName());
        }
        else
            return context.getContextHandler();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        boolean useOutputDirectByteBuffers = true;
        if (resp instanceof ServletContextResponse.ServletApiResponse servletApiResponse)
            useOutputDirectByteBuffers = servletApiResponse.getResponse().getWrapped().getRequest().getConnectionMetaData().getHttpConfiguration().isUseOutputDirectByteBuffers();

        HttpContent content = _resourceService.getContent(req.getServletPath(), resp.getBufferSize());
        if (content == null)
        {
            // no content
            resp.setStatus(404);
        }
        else
        {
            // serve content
            try
            {
                _resourceService.doGet(new ServletGenericRequest(req), new ServletGenericResponse(resp, useOutputDirectByteBuffers), Callback.NOOP, content);
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

    private static class ServletGenericRequest implements ResourceService.GenericRequest
    {
        private final HttpServletRequest _request;
        private final HttpFields _httpFields;

        ServletGenericRequest(HttpServletRequest request)
        {
            this._request = request;
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
            return HttpURI.from(_request.getRequestURI());
        }

        @Override
        public String getPathInContext()
        {
            return _request.getRequestURI();
        }

        @Override
        public String getContextPath()
        {
            return _request.getContextPath();
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

    private static class ServletGenericResponse implements ResourceService.GenericResponse
    {
        private final HttpServletResponse _response;
        private final HttpFields.Mutable _httpFields;
        private final boolean _useOutputDirectByteBuffers;

        public ServletGenericResponse(HttpServletResponse response, boolean useOutputDirectByteBuffers)
        {
            _response = response;
            _httpFields = new HttpServletResponseHttpFields(response);
            _useOutputDirectByteBuffers = useOutputDirectByteBuffers;
        }

        @Override
        public HttpFields.Mutable getHeaders()
        {
            return _httpFields;
        }

        @Override
        public int getOutputBufferSize()
        {
            return _response.getBufferSize();
        }

        @Override
        public boolean isCommitted()
        {
            return _response.isCommitted();
        }

        @Override
        public boolean isUseOutputDirectByteBuffers()
        {
            return _useOutputDirectByteBuffers;
        }

        @Override
        public void sendRedirect(Callback callback, String uri)
        {
            try
            {
                _response.sendRedirect(uri);
                callback.succeeded();
            }
            catch (Throwable x)
            {
                callback.failed(x);
            }
        }

        @Override
        public void writeError(Callback callback, int status)
        {
            _response.setStatus(status);
            callback.succeeded();
        }

        @Override
        public void write(HttpContent content, Callback callback)
        {
            ByteBuffer buffer = content.getBuffer();
            if (buffer != null)
            {
                writeLast(buffer, callback);
            }
            else
            {
                try
                {
                    try (InputStream inputStream = Files.newInputStream(content.getResource().getPath());
                         OutputStream outputStream = _response.getOutputStream())
                    {
                        IO.copy(inputStream, outputStream);
                    }
                    callback.succeeded();
                }
                catch (Throwable x)
                {
                    callback.failed(x);
                }
            }
        }

        @Override
        public void writeLast(ByteBuffer byteBuffer, Callback callback)
        {
            try
            {
                ServletOutputStream outputStream = _response.getOutputStream();
                byte[] bytes = new byte[byteBuffer.remaining()];
                byteBuffer.get(bytes);
                outputStream.write(bytes);
                outputStream.close();

                callback.succeeded();
            }
            catch (Throwable x)
            {
                callback.failed(x);
            }
        }
    }

    private static class ServletResourceService extends ResourceService
    {
        private static final Logger LOG = LoggerFactory.getLogger(ServletResourceService.class);

        @Override
        protected boolean welcome(GenericRequest rq, GenericResponse rs, Callback callback) throws IOException
        {
            HttpServletRequest request = ((ServletGenericRequest)rq)._request;
            HttpServletResponse response = ((ServletGenericResponse)rs)._response;
            String pathInContext = rq.getPathInContext();
            WelcomeFactory welcomeFactory = getWelcomeFactory();
            String welcome = welcomeFactory == null ? null : welcomeFactory.getWelcomeFile(pathInContext);
            boolean included = request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI) != null;

            if (welcome != null)
            {
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

                    String uri = URIUtil.encodePath(URIUtil.addPaths(request.getContextPath(), welcome));
                    String q = request.getQueryString();
                    if (q != null && !q.isEmpty())
                        uri += "?" + q;

                    response.sendRedirect(response.encodeRedirectURL(uri));
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
                    }
                    catch (ServletException e)
                    {
                        callback.failed(e);
                    }
                }
                return true;
            }
            return false;
        }

        @Override
        protected boolean passConditionalHeaders(GenericRequest request, GenericResponse response, HttpContent content, Callback callback) throws IOException
        {
            boolean included = ((ServletGenericRequest)request)._request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI) != null;
            if (included)
                return true;
            return super.passConditionalHeaders(request, response, content, callback);
        }
    }
}
