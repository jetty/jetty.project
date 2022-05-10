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
import java.util.Iterator;
import java.util.ListIterator;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

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
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;

public class DefaultServlet extends HttpServlet
{
    private ResourceService _resourceService;

    @Override
    public void init(ServletConfig config) throws ServletException
    {
        ContextHandler contextHandler = initContextHandler(config.getServletContext());

        _resourceService = new ResourceService();
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
            if (servletContext instanceof ContextHandler.Context)
                return ((ContextHandler.Context)servletContext).getContextHandler();
            else
                throw new IllegalArgumentException("The servletContext " + servletContext + " " +
                    servletContext.getClass().getName() + " is not " + ContextHandler.Context.class.getName());
        }
        else
            return context.getContextHandler();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        Request request;
        Response response;
        int outputBufferSize;
        if (resp instanceof ServletContextResponse.ServletApiResponse servletApiResponse)
        {
            // Fast path: unwrap and use the internal request/response.
            response = servletApiResponse.getResponse().getWrapped();
            request = response.getRequest();
            outputBufferSize = request.getConnectionMetaData().getHttpConfiguration().getOutputBufferSize();
        }
        else
        {
            // Slow path: wrap the servlet API when the internal request/response cannot be accessed.
            request = new ServletRequest(req);
            response = new ServletResponse(request, resp);
            outputBufferSize = resp.getBufferSize();
        }

        HttpContent content = _resourceService.getContent(req.getServletPath(), outputBufferSize);
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
                FutureCallback callback = new FutureCallback();
                _resourceService.doGet(request, response, callback, content);
                callback.get();
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
        // TODO use service
        super.doHead(req, resp);
    }

    static class ServletRequest implements Request
    {
        private final HttpServletRequest servletRequest;

        public ServletRequest(HttpServletRequest servletRequest)
        {
            this.servletRequest = servletRequest;
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void fail(Throwable failure)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getId()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Components getComponents()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public ConnectionMetaData getConnectionMetaData()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getMethod()
        {
            return servletRequest.getMethod();
        }

        @Override
        public HttpURI getHttpURI()
        {
            return HttpURI.from(servletRequest.getRequestURI());
        }

        @Override
        public Context getContext()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getPathInContext()
        {
            return servletRequest.getServletPath();
        }

        @Override
        public HttpFields getHeaders()
        {
            return () ->
            {
                // TODO implement on top of servletRequest.getHeaderNames() / servletRequest.getHeaders()
                return new Iterator<>()
                {
                    @Override
                    public boolean hasNext()
                    {
                        return false;
                    }

                    @Override
                    public HttpField next()
                    {
                        return null;
                    }
                };
            };
        }

        @Override
        public long getTimeStamp()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isSecure()
        {
            return servletRequest.isSecure();
        }

        @Override
        public Content.Chunk read()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void push(MetaData.Request request)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean addErrorListener(Predicate<Throwable> onError)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addHttpStreamWrapper(Function<HttpStream, HttpStream.Wrapper> wrapper)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object removeAttribute(String name)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object setAttribute(String name, Object attribute)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object getAttribute(String name)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearAttributes()
        {
            throw new UnsupportedOperationException();
        }
    }

    static class ServletResponse implements Response
    {
        private final Request request;
        private final HttpServletResponse servletResponse;
        private final ServletOutputStream outputStream;

        public ServletResponse(Request request, HttpServletResponse servletResponse) throws IOException
        {
            this.request = request;
            this.servletResponse = servletResponse;
            this.outputStream = servletResponse.getOutputStream();
        }

        @Override
        public Request getRequest()
        {
            return request;
        }

        @Override
        public int getStatus()
        {
            return servletResponse.getStatus();
        }

        @Override
        public void setStatus(int code)
        {
            servletResponse.setStatus(code);
        }

        @Override
        public HttpFields.Mutable getHeaders()
        {
            return () ->
            {
                // TODO implement on top of servletResponse.getHeaderNames() / servletResponse.getHeaders() / servletResponse.setHeader()
                return new ListIterator<>()
                {
                    @Override
                    public boolean hasNext()
                    {
                        return false;
                    }

                    @Override
                    public HttpField next()
                    {
                        return null;
                    }

                    @Override
                    public boolean hasPrevious()
                    {
                        return false;
                    }

                    @Override
                    public HttpField previous()
                    {
                        return null;
                    }

                    @Override
                    public int nextIndex()
                    {
                        return 0;
                    }

                    @Override
                    public int previousIndex()
                    {
                        return 0;
                    }

                    @Override
                    public void remove()
                    {

                    }

                    @Override
                    public void set(HttpField httpField)
                    {

                    }

                    @Override
                    public void add(HttpField httpField)
                    {

                    }
                };
            };
        }

        @Override
        public HttpFields.Mutable getOrCreateTrailers()
        {
            return () ->
            {
                // TODO implement on top of servletResponse.getHeaderNames() / servletResponse.getHeaders() / servletResponse.setHeader()
                return new ListIterator<>()
                {
                    @Override
                    public boolean hasNext()
                    {
                        return false;
                    }

                    @Override
                    public HttpField next()
                    {
                        return null;
                    }

                    @Override
                    public boolean hasPrevious()
                    {
                        return false;
                    }

                    @Override
                    public HttpField previous()
                    {
                        return null;
                    }

                    @Override
                    public int nextIndex()
                    {
                        return 0;
                    }

                    @Override
                    public int previousIndex()
                    {
                        return 0;
                    }

                    @Override
                    public void remove()
                    {

                    }

                    @Override
                    public void set(HttpField httpField)
                    {

                    }

                    @Override
                    public void add(HttpField httpField)
                    {

                    }
                };
            };
        }

        @Override
        public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
        {
            try
            {
                byte[] bytes = new byte[byteBuffer.remaining()];
                byteBuffer.get(bytes);
                outputStream.write(bytes);
                if (last)
                    outputStream.close();
                callback.succeeded();
            }
            catch (Throwable x)
            {
                callback.failed(x);
            }
        }

        @Override
        public boolean isCommitted()
        {
            return servletResponse.isCommitted();
        }

        @Override
        public boolean isCompletedSuccessfully()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void reset()
        {
            throw new UnsupportedOperationException();
        }
    }
}
