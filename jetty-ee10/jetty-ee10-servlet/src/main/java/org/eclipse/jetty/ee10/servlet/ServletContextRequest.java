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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.List;

import jakarta.servlet.AsyncListener;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestAttributeListener;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.pathmap.MatchedPath;
import org.eclipse.jetty.http.pathmap.MatchedResource;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextRequest;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.IO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServletContextRequest extends ContextRequest
{
    public static final String __MULTIPART_CONFIG_ELEMENT = "org.eclipse.jetty.multipartConfig";
    private static final Logger LOG = LoggerFactory.getLogger(ServletContextRequest.class);
    static final int INPUT_NONE = 0;
    static final int INPUT_STREAM = 1;
    static final int INPUT_READER = 2;

    static final Fields NO_PARAMS = new Fields(new Fields(), true);
    static final Fields BAD_PARAMS = new Fields(new Fields(), true);

    public static ServletContextRequest getServletContextRequest(ServletRequest request)
    {
        if (request instanceof ServletApiRequest)
            return ((ServletApiRequest)request).getRequest();

        Object channel = request.getAttribute(ServletChannel.class.getName());
        if (channel instanceof ServletChannel)
            return ((ServletChannel)channel).getServletContextRequest();

        while (request instanceof ServletRequestWrapper)
        {
            request = ((ServletRequestWrapper)request).getRequest();
        }

        if (request instanceof ServletApiRequest)
            return ((ServletApiRequest)request).getRequest();

        throw new IllegalStateException("could not find %s for %s".formatted(ServletContextRequest.class.getSimpleName(), request));
    }

    private final List<ServletRequestAttributeListener> _requestAttributeListeners = new ArrayList<>();
    private final ServletApiRequest _httpServletRequest;
    private final ServletContextResponse _response;
    final ServletHandler.MappedServlet _mappedServlet;
    private final HttpInput _httpInput;
    private final String _pathInContext;
    private final ServletChannel _servletChannel;
    private final PathSpec _pathSpec;
    final MatchedPath _matchedPath;
    private Charset _queryEncoding;
    private HttpFields _trailers;

    protected ServletContextRequest(
        ServletContextHandler.ServletContextApi servletContextApi,
        ServletChannel servletChannel,
        Request request,
        Response response,
        String pathInContext,
        MatchedResource<ServletHandler.MappedServlet> matchedResource)
    {
        super(servletContextApi.getContext(), request);
        _servletChannel = servletChannel;
        _httpServletRequest = newServletApiRequest();
        _mappedServlet = matchedResource.getResource();
        _httpInput = _servletChannel.getHttpInput();
        _pathInContext = pathInContext;
        _pathSpec = matchedResource.getPathSpec();
        _matchedPath = matchedResource.getMatchedPath();
        _response =  newServletContextResponse(response);
    }

    protected ServletApiRequest newServletApiRequest()
    {
        return new ServletApiRequest(this);
    }

    protected ServletContextResponse newServletContextResponse(Response response)
    {
        return new ServletContextResponse(_servletChannel, this, response);
    }

    public String getPathInContext()
    {
        return _pathInContext;
    }

    public PathSpec getPathSpec()
    {
        return _pathSpec;
    }

    public MatchedPath getMatchedPath()
    {
        return _matchedPath;
    }

    @Override
    public HttpFields getTrailers()
    {
        return _trailers;
    }

    void setTrailers(HttpFields trailers)
    {
        _trailers = trailers;
    }

    public ServletRequestState getState()
    {
        return _servletChannel.getState();
    }

    public ServletContextResponse getResponse()
    {
        return _response;
    }

    @Override
    public ServletContextHandler.ServletScopedContext getContext()
    {
        return (ServletContextHandler.ServletScopedContext)super.getContext();
    }

    public HttpInput getHttpInput()
    {
        return _httpInput;
    }

    public HttpOutput getHttpOutput()
    {
        return _response.getHttpOutput();
    }

    public void errorClose()
    {
        // TODO Actually make the response status and headers immutable temporarily
        _response.getHttpOutput().softClose();
    }

    public boolean isHead()
    {
        return HttpMethod.HEAD.is(getMethod());
    }

    /**
     * Set the character encoding used for the query string. This call will effect the return of getQueryString and getParamaters. It must be called before any
     * getParameter methods.
     *
     * The request attribute "org.eclipse.jetty.server.Request.queryEncoding" may be set as an alternate method of calling setQueryEncoding.
     *
     * @param queryEncoding the URI query character encoding
     */
    public void setQueryEncoding(String queryEncoding)
    {
        _queryEncoding = Charset.forName(queryEncoding);
    }

    public Charset getQueryEncoding()
    {
        return _queryEncoding;
    }

    @Override
    public Object getAttribute(String name)
    {
        // return hidden attributes for request logging
        // TODO does this actually work?   Does the request logger have the wrapped request?
        return switch (name)
        {
            case "o.e.j.s.s.ServletScopedRequest.request" -> _httpServletRequest;
            case "o.e.j.s.s.ServletScopedRequest.response" -> _response.getHttpServletResponse();
            case "o.e.j.s.s.ServletScopedRequest.servlet" -> _mappedServlet.getServletPathMapping(getPathInContext()).getServletName();
            case "o.e.j.s.s.ServletScopedRequest.url-pattern" -> _mappedServlet.getServletPathMapping(getPathInContext()).getPattern();
            default -> super.getAttribute(name);
        };
    }

    @Override
    public Object removeAttribute(String name)
    {
        return super.removeAttribute(name);
    }

    @Override
    public Object setAttribute(String name, Object attribute)
    {
        return super.setAttribute(name, attribute);
    }

    /**
     * @return The current {@link ContextHandler.ScopedContext context} used for this error handling for this request.  If the request is asynchronous,
     * then it is the context that called async. Otherwise it is the last non-null context passed to #setContext
     */
    public ServletContextHandler.ServletScopedContext getErrorContext()
    {
        // TODO: review.
        return _servletChannel.getContext();
    }

    ServletRequestState getServletRequestState()
    {
        return _servletChannel.getState();
    }

    ServletChannel getServletChannel()
    {
        return _servletChannel;
    }

    public HttpServletRequest getHttpServletRequest()
    {
        return _httpServletRequest;
    }

    public ServletApiRequest getServletApiRequest()
    {
        return _httpServletRequest;
    }

    public HttpServletResponse getHttpServletResponse()
    {
        return _response.getHttpServletResponse();
    }

    public ServletHandler.MappedServlet getMappedServlet()
    {
        return _mappedServlet;
    }

    public String getServletName()
    {
        return _mappedServlet.getServletHolder().getName();
    }

    public List<ServletRequestAttributeListener> getRequestAttributeListeners()
    {
        return _requestAttributeListeners;
    }

    public void addEventListener(final EventListener listener)
    {
        if (listener instanceof ServletRequestAttributeListener)
            _requestAttributeListeners.add((ServletRequestAttributeListener)listener);
        if (listener instanceof AsyncListener)
            throw new IllegalArgumentException(listener.getClass().toString());
    }

    public void removeEventListener(final EventListener listener)
    {
        _requestAttributeListeners.remove(listener);
    }

    /**
     * Compares fields to {@link #NO_PARAMS} by Reference
     *
     * @param fields The parameters to compare to {@link #NO_PARAMS}
     * @return {@code true} if the fields reference is equal to {@link #NO_PARAMS}, otherwise {@code false}
     */
    static boolean isNoParams(Fields fields)
    {
        @SuppressWarnings("ReferenceEquality")
        boolean isNoParams = (fields == NO_PARAMS);
        return isNoParams;
    }
}
