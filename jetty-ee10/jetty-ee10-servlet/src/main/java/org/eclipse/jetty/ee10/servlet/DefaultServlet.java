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
import java.util.Enumeration;

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
        private final HttpServletRequest request;
        private final HttpFields httpFields;

        ServletGenericRequest(HttpServletRequest request)
        {
            this.request = request;
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
            httpFields = fields.asImmutable();
        }

        @Override
        public HttpFields getHeaders()
        {
            return httpFields;
        }

        @Override
        public HttpURI getHttpURI()
        {
            return HttpURI.from(request.getRequestURI());
        }

        @Override
        public String getPathInContext()
        {
            return request.getRequestURI();
        }

        @Override
        public String getContextPath()
        {
            return request.getContextPath();
        }
    }

    private static class ServletGenericResponse implements ResourceService.GenericResponse
    {
        private final HttpServletResponse response;
        private final boolean useOutputDirectByteBuffers;

        public ServletGenericResponse(HttpServletResponse response, boolean useOutputDirectByteBuffers)
        {
            this.response = response;
            this.useOutputDirectByteBuffers = useOutputDirectByteBuffers;
        }

        @Override
        public boolean containsHeader(HttpHeader header)
        {
            return response.containsHeader(header.asString());
        }

        @Override
        public void putHeader(HttpField header)
        {
            response.addHeader(header.getName(), header.getValue());
        }

        @Override
        public void putHeader(HttpHeader header, String value)
        {
            response.addHeader(header.asString(), value);
        }

        @Override
        public void putHeaderLong(HttpHeader header, long value)
        {
            response.addHeader(header.asString(), Long.toString(value));
        }

        @Override
        public int getOutputBufferSize()
        {
            return response.getBufferSize();
        }

        @Override
        public boolean isCommitted()
        {
            return response.isCommitted();
        }

        @Override
        public boolean isUseOutputDirectByteBuffers()
        {
            return useOutputDirectByteBuffers;
        }

        @Override
        public void sendRedirect(Callback callback, String uri)
        {
            try
            {
                response.sendRedirect(uri);
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
            response.setStatus(status);
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
                         OutputStream outputStream = response.getOutputStream())
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
                ServletOutputStream outputStream = response.getOutputStream();
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
            HttpServletRequest request = ((ServletGenericRequest)rq).request;
            HttpServletResponse response = ((ServletGenericResponse)rs).response;
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
            boolean included = ((ServletGenericRequest)request).request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI) != null;
            if (included)
                return true;
            return super.passConditionalHeaders(request, response, content, callback);
        }
    }
}
