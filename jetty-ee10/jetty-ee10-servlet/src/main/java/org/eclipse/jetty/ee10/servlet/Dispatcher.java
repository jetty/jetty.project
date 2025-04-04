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

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.eclipse.jetty.ee10.servlet.util.ServletOutputStreamWrapper;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.pathmap.MatchedResource;
import org.eclipse.jetty.io.WriterOutputStream;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.UrlEncoded;

public class Dispatcher implements RequestDispatcher
{
    /**
     * Dispatch include attribute names
     */
    public static final String __INCLUDE_PREFIX = "jakarta.servlet.include.";

    /**
     * Dispatch include attribute names
     */
    public static final String __FORWARD_PREFIX = "jakarta.servlet.forward.";
    
    /**
     * Name of original request attribute
     */ 
    public static final String __ORIGINAL_REQUEST = "org.eclipse.jetty.originalRequest";

    public static final String JETTY_INCLUDE_HEADER_PREFIX = "org.eclipse.jetty.server.include.";

    private final ServletContextHandler _contextHandler;
    private final HttpURI _uri;
    private final String _decodedPathInContext;
    private final String _named;
    private final ServletHandler.MappedServlet _mappedServlet;
    private final ServletHandler _servletHandler;
    private final ServletPathMapping _servletPathMapping;

    public Dispatcher(ServletContextHandler contextHandler, HttpURI uri, String decodedPathInContext)
    {
        _contextHandler = contextHandler;
        _uri = uri.asImmutable();
        _decodedPathInContext = decodedPathInContext;
        _named = null;

        _servletHandler = _contextHandler.getServletHandler();
        MatchedResource<ServletHandler.MappedServlet> matchedServlet = _servletHandler.getMatchedServlet(decodedPathInContext);
        if (matchedServlet == null)
            throw new IllegalArgumentException("No servlet matching: " + decodedPathInContext);
        _mappedServlet = matchedServlet.getResource();
        _servletPathMapping = _mappedServlet.getServletPathMapping(_decodedPathInContext, matchedServlet.getMatchedPath());
        if (_servletPathMapping == null)
            throw new IllegalArgumentException("No servlet path mapping: " + _servletPathMapping);
    }

    public Dispatcher(ServletContextHandler contextHandler, String name) throws IllegalStateException
    {
        _contextHandler = contextHandler;
        _uri = null;
        _decodedPathInContext = null;
        _named = name;

        _servletHandler = _contextHandler.getServletHandler();
        _mappedServlet = _servletHandler.getMappedServlet(name);
        _servletPathMapping = null;
    }

    public void error(ServletRequest request, ServletResponse response) throws ServletException, IOException
    {
        assert _named == null : "not allowed to have a named dispatch on error";
        assert _servletPathMapping != null : "Servlet Path Mapping required";
        assert _uri != null : "URI is required";

        HttpServletRequest httpRequest = (request instanceof HttpServletRequest) ? (HttpServletRequest)request : new ServletRequestHttpWrapper(request);
        HttpServletResponse httpResponse = (response instanceof HttpServletResponse) ? (HttpServletResponse)response : new ServletResponseHttpWrapper(response);

        _mappedServlet.handle(_servletHandler, _decodedPathInContext, new ErrorRequest(httpRequest), httpResponse);
    }

    @Override
    public void forward(ServletRequest request, ServletResponse response) throws ServletException, IOException
    {
        HttpServletRequest httpRequest = (request instanceof HttpServletRequest) ? (HttpServletRequest)request : new ServletRequestHttpWrapper(request);
        HttpServletResponse httpResponse = (response instanceof HttpServletResponse) ? (HttpServletResponse)response : new ServletResponseHttpWrapper(response);

        ServletContextRequest servletContextRequest = ServletContextRequest.getServletContextRequest(request);
        servletContextRequest.getServletContextResponse().resetForForward();
        _mappedServlet.handle(_servletHandler, _decodedPathInContext, new ForwardRequest(httpRequest), httpResponse);

        // If we are not async and not closed already, then close via the possibly wrapped response.
        if (!servletContextRequest.getState().isAsync() && !servletContextRequest.getServletContextResponse().hasLastWrite())
        {
            Closeable closeable;
            try
            {
                closeable = response.getOutputStream();
            }
            catch (IllegalStateException e)
            {
                closeable = response.getWriter();
            }
            IO.close(closeable);
        }
    }

    @Override
    public void include(ServletRequest request, ServletResponse response) throws ServletException, IOException
    {
        HttpServletRequest httpRequest = (request instanceof HttpServletRequest) ? (HttpServletRequest)request : new ServletRequestHttpWrapper(request);
        HttpServletResponse httpResponse = (response instanceof HttpServletResponse) ? (HttpServletResponse)response : new ServletResponseHttpWrapper(response);
        ServletContextResponse servletContextResponse = ServletContextResponse.getServletContextResponse(response);

        IncludeResponse includeResponse = new IncludeResponse(httpResponse);
        try
        {
            _mappedServlet.handle(_servletHandler, _decodedPathInContext, new IncludeRequest(httpRequest), includeResponse);
        }
        finally
        {
            includeResponse.onIncluded();
            servletContextResponse.included();
        }
    }

    public void async(ServletRequest request, ServletResponse response) throws ServletException, IOException
    {
        HttpServletRequest httpRequest = (request instanceof HttpServletRequest) ? (HttpServletRequest)request : new ServletRequestHttpWrapper(request);
        HttpServletResponse httpResponse = (response instanceof HttpServletResponse) ? (HttpServletResponse)response : new ServletResponseHttpWrapper(response);

        _mappedServlet.handle(_servletHandler, _decodedPathInContext, new AsyncRequest(httpRequest), httpResponse);
    }

    public class ParameterRequestWrapper extends HttpServletRequestWrapper
    {
        private Fields _parameters = null;

        public ParameterRequestWrapper(HttpServletRequest request)
        {
            super(request);
        }

        private Fields getParameters()
        {
            if (_parameters == null)
            {
                String targetQuery = (_uri == null) ? null : _uri.getQuery();

                if (getRequest() instanceof ServletApiRequest servletApiRequest)
                {
                    Fields parameters = servletApiRequest.getParameters();

                    if (targetQuery == null)
                    {
                        _parameters = parameters;
                    }
                    else
                    {
                        _parameters = new Fields(true);
                        UrlEncoded.decodeTo(targetQuery, _parameters::add, UrlEncoded.ENCODING);
                        _parameters.addAll(parameters);
                    }
                }
                else
                {
                    _parameters = new Fields(true);
                    // Have to assume ENCODING because we can't know otherwise.
                    if (targetQuery != null)
                        UrlEncoded.decodeTo(targetQuery, _parameters::add, UrlEncoded.ENCODING);
                    for (Enumeration<String> names = getRequest().getParameterNames(); names.hasMoreElements(); )
                    {
                        String name = names.nextElement();
                        _parameters.add(name, getRequest().getParameterValues(name));
                    }
                }
            }
            return _parameters;
        }

        @Override
        public String getQueryString()
        {
            // The current behaviour is to return the target query if not null, else the original query is returned.
            // This means that the query string does not match the parameter map, which is merged for most dispatcher.
            // The specification is not clear on how the query should be handled.  It is in ongoing discussion in
            // https://github.com/jakartaee/servlet/issues/309
            // Currently the older jetty behaviour (merging the query string) has been replaced by the behaviour used
            // by other containers in order to pass the TCK.
            if (_uri != null)
            {
                String targetQuery = _uri.getQuery();
                if (!StringUtil.isEmpty(targetQuery))
                    return targetQuery;
            }
            return super.getQueryString();
        }

        @Override
        public String getParameter(String name)
        {
            return getParameters().getValue(name);
        }

        @Override
        public Map<String, String[]> getParameterMap()
        {
            return Collections.unmodifiableMap(getParameters().toStringArrayMap());
        }

        @Override
        public Enumeration<String> getParameterNames()
        {
            return Collections.enumeration(getParameters().getNames());
        }

        @Override
        public String[] getParameterValues(String name)
        {
            List<String> vals = getParameters().getValues(name);
            if (vals == null)
                return null;
            return vals.toArray(new String[0]);
        }

        @Override
        public String toString()
        {
            return "%s@%x{%s}".formatted(getClass().getSimpleName(), hashCode(), getRequest());
        }
    }

    private class ForwardRequest extends ParameterRequestWrapper
    {
        private final HttpServletRequest _httpServletRequest;

        public ForwardRequest(HttpServletRequest httpRequest)
        {
            super(httpRequest);
            _httpServletRequest = httpRequest;
        }

        @Override
        public DispatcherType getDispatcherType()
        {
            return DispatcherType.FORWARD;
        }

        @Override
        public String getPathInfo()
        {
            if (_servletPathMapping == null)
                return super.getPathInfo();
            return _servletPathMapping.getPathInfo();
        }

        @Override
        public String getServletPath()
        {
            if (_servletPathMapping == null)
                return super.getServletPath();
            return _servletPathMapping.getServletPath();
        }

        @Override
        public HttpServletMapping getHttpServletMapping()
        {
            if (_named != null)
                return super.getHttpServletMapping();

            return _servletPathMapping;
        }

        @Override
        public String getRequestURI()
        {
            return _uri == null ? super.getRequestURI() : _uri.getPath();
        }

        @Override
        public StringBuffer getRequestURL()
        {
            return _uri == null ? super.getRequestURL() :  new StringBuffer(HttpURI.build(_uri).query(null).scheme(super.getScheme()).host(super.getServerName()).port(super.getServerPort()).asString());
        }

        @Override
        public Object getAttribute(String name)
        {
            if (name == null)
                return null;
            
            //Servlet Spec 9.4.2 no forward attributes if a named dispatcher
            if (_named != null && name.startsWith(__FORWARD_PREFIX))
                return null;

            //Servlet Spec 9.4.2 must return the values from the original request
            if (name.startsWith(__FORWARD_PREFIX))
            {
                HttpServletRequest originalRequest = (HttpServletRequest)super.getAttribute(__ORIGINAL_REQUEST);
                if (originalRequest == null)
                    originalRequest = _httpServletRequest;

                return switch (name)
                {
                    case RequestDispatcher.FORWARD_REQUEST_URI -> originalRequest.getRequestURI();
                    case RequestDispatcher.FORWARD_SERVLET_PATH -> originalRequest.getServletPath();
                    case RequestDispatcher.FORWARD_PATH_INFO -> originalRequest.getPathInfo();
                    case RequestDispatcher.FORWARD_CONTEXT_PATH -> originalRequest.getContextPath();
                    case RequestDispatcher.FORWARD_MAPPING -> originalRequest.getHttpServletMapping();
                    case RequestDispatcher.FORWARD_QUERY_STRING -> originalRequest.getQueryString();
                    default -> super.getAttribute(name);
                };
            }

            switch (name)
            {
                case __ORIGINAL_REQUEST ->
                {
                    HttpServletRequest originalRequest = (HttpServletRequest)super.getAttribute(name);
                    return originalRequest == null ? _httpServletRequest : originalRequest;
                }
                // Forward should hide include.
                case RequestDispatcher.INCLUDE_MAPPING, RequestDispatcher.INCLUDE_SERVLET_PATH, RequestDispatcher.INCLUDE_PATH_INFO, RequestDispatcher.INCLUDE_REQUEST_URI, RequestDispatcher.INCLUDE_CONTEXT_PATH, RequestDispatcher.INCLUDE_QUERY_STRING ->
                {
                    return null;
                }
                case ServletContextRequest.MULTIPART_CONFIG_ELEMENT ->
                {
                    // If we already have future parts, return the configuration of the wrapped request.
                    if (super.getAttribute(ServletMultiPartFormData.class.getName()) != null)
                        return super.getAttribute(name);
                    // otherwise, return the configuration of this mapping
                    return _mappedServlet.getServletHolder().getMultipartConfigElement();
                }

                default ->
                {
                    return super.getAttribute(name);
                }
            }
        }

        @Override
        public Enumeration<String> getAttributeNames()
        {
            ArrayList<String> names = new ArrayList<>(Collections.list(super.getAttributeNames()));

            //only return the multipart attribute name if this servlet mapping has multipart config
            if (names.contains(ServletContextRequest.MULTIPART_CONFIG_ELEMENT) && _mappedServlet.getServletHolder().getMultipartConfigElement() == null)
                names.remove(ServletContextRequest.MULTIPART_CONFIG_ELEMENT);
            
            //Servlet Spec 9.4.2 no forward attributes if a named dispatcher
            if (_named != null)
                return Collections.enumeration(names);
            
            names.add(RequestDispatcher.FORWARD_REQUEST_URI);
            names.add(RequestDispatcher.FORWARD_SERVLET_PATH);
            names.add(RequestDispatcher.FORWARD_PATH_INFO);
            names.add(RequestDispatcher.FORWARD_CONTEXT_PATH);
            names.add(RequestDispatcher.FORWARD_MAPPING);
            names.add(RequestDispatcher.FORWARD_QUERY_STRING);
            return Collections.enumeration(names);
        }
    }

    private class IncludeRequest extends ParameterRequestWrapper
    {
        private final HttpServletRequest _httpServletRequest;

        public IncludeRequest(HttpServletRequest request)
        {
            super(request);
            _httpServletRequest = request;
        }

        @Override
        public DispatcherType getDispatcherType()
        {
            return DispatcherType.INCLUDE;
        }

        @Override
        public Object getAttribute(String name)
        {
            if (name == null)
                return null;
            
            //Servlet Spec 9.3.1 no include attributes if a named dispatcher
            if (_named != null && name.startsWith(__INCLUDE_PREFIX))
                return null;

            return switch (name)
            {
                case RequestDispatcher.INCLUDE_MAPPING -> _servletPathMapping;
                case RequestDispatcher.INCLUDE_SERVLET_PATH -> _servletPathMapping.getServletPath();
                case RequestDispatcher.INCLUDE_PATH_INFO -> _servletPathMapping.getPathInfo();
                case RequestDispatcher.INCLUDE_REQUEST_URI -> (_uri == null) ? null : _uri.getPath();
                case RequestDispatcher.INCLUDE_CONTEXT_PATH -> _httpServletRequest.getContextPath();
                case RequestDispatcher.INCLUDE_QUERY_STRING -> (_uri == null) ? null : _uri.getQuery();
                default -> super.getAttribute(name);
            };
        }

        @Override
        public Enumeration<String> getAttributeNames()
        {
            //Servlet Spec 9.3.1 no include attributes if a named dispatcher
            ArrayList<String> names = new ArrayList<>(Collections.list(super.getAttributeNames()));
            if (_named != null)
                return Collections.enumeration(names);
            
            names.add(RequestDispatcher.INCLUDE_MAPPING);
            names.add(RequestDispatcher.INCLUDE_SERVLET_PATH);
            names.add(RequestDispatcher.INCLUDE_PATH_INFO);
            names.add(RequestDispatcher.INCLUDE_REQUEST_URI);
            names.add(RequestDispatcher.INCLUDE_CONTEXT_PATH);
            names.add(RequestDispatcher.INCLUDE_QUERY_STRING);
            return Collections.enumeration(names);
        }

        @Override
        public String getQueryString()
        {
            return _httpServletRequest.getQueryString();
        }
    }

    private static class IncludeResponse extends HttpServletResponseWrapper
    {
        ServletOutputStream _servletOutputStream;
        PrintWriter _printWriter;
        PrintWriter _mustFlush;
        
        public IncludeResponse(HttpServletResponse response)
        {
            super(response);
        }

        public void onIncluded()
        {
            if (_mustFlush != null)
                _mustFlush.flush();
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException
        {
            if (_printWriter != null)
                throw new IllegalStateException("getWriter() called");
            if (_servletOutputStream == null)
            {
                try
                {
                    _servletOutputStream = new ServletOutputStreamWrapper(getResponse().getOutputStream())
                    {
                        @Override
                        public void close()
                        {
                            // NOOP for include.
                        }
                    };
                }
                catch (IllegalStateException ise)
                {
                    OutputStream os = new WriterOutputStream(getResponse().getWriter(), getResponse().getCharacterEncoding());
                    _servletOutputStream = new ServletOutputStream()
                    {
                        @Override
                        public boolean isReady()
                        {
                            return true;
                        }

                        @Override
                        public void setWriteListener(WriteListener writeListener)
                        {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public void write(int b) throws IOException
                        {
                            os.write(b);
                        }

                        @Override
                        public void write(byte[] b) throws IOException
                        {
                            os.write(b);
                        }

                        @Override
                        public void write(byte[] b, int off, int len) throws IOException
                        {
                            os.write(b, off, len);
                        }

                        @Override
                        public void flush() throws IOException
                        {
                            os.flush();
                        }

                        @Override
                        public void close()
                        {
                            // NOOP for include.
                        }
                    };
                }
            }
            return _servletOutputStream;
        }

        @Override
        public PrintWriter getWriter() throws IOException
        {
            if (_servletOutputStream != null)
                throw new IllegalStateException("getOutputStream called");
            if (_printWriter == null)
            {
                try
                {
                    _printWriter = super.getWriter();
                }
                catch (IllegalStateException ise)
                {
                    _printWriter = _mustFlush = new PrintWriter(new OutputStreamWriter(super.getOutputStream(), super.getCharacterEncoding()));
                }
            }
            return _printWriter;
        }

        @Override
        public void setCharacterEncoding(String charset)
        {
            // NOOP for include.
        }

        @Override
        public void setLocale(Locale loc)
        {
            // NOOP for include.
        }

        @Override
        public void setContentLength(int len)
        {
            // NOOP for include.
        }

        @Override
        public void setContentLengthLong(long len)
        {
            // NOOP for include.
        }

        @Override
        public void setContentType(String type)
        {
            // NOOP for include.
        }

        @Override
        public void reset()
        {
            // NOOP for include.
        }

        @Override
        public void resetBuffer()
        {
            // NOOP for include.
        }

        @Override
        public void setDateHeader(String name, long date)
        {
            // NOOP for include.
        }

        @Override
        public void addDateHeader(String name, long date)
        {
            // NOOP for include.
        }

        @Override
        public void setHeader(String name, String value)
        {
            //implement jetty-specific extension to include to allow headers
            //to be set
            if (!StringUtil.isBlank(name) && name.startsWith(JETTY_INCLUDE_HEADER_PREFIX))
                super.setHeader(name.substring(JETTY_INCLUDE_HEADER_PREFIX.length()), value);
        }

        @Override
        public void addHeader(String name, String value)
        {
            //implement jetty-specific extension to include to allow headers
            //to be set
            if (!StringUtil.isBlank(name) && name.startsWith(JETTY_INCLUDE_HEADER_PREFIX))
                super.addHeader(name.substring(JETTY_INCLUDE_HEADER_PREFIX.length()), value);
        }

        @Override
        public void setIntHeader(String name, int value)
        {
            // NOOP for include.
        }

        @Override
        public void addIntHeader(String name, int value)
        {
            // NOOP for include.
        }

        @Override
        public void setStatus(int sc)
        {
            // NOOP for include.
        }

        @Override
        public void sendError(int sc, String msg) throws IOException
        {
            // NOOP for include.
        }

        @Override
        public void sendError(int sc) throws IOException
        {
            // NOOP for include.
        }

        @Override
        public void sendRedirect(String location) throws IOException
        {
            // NOOP for include.
        }
    }

    private class AsyncRequest extends ParameterRequestWrapper
    {
        private final HttpServletRequest _httpServletRequest;

        public AsyncRequest(HttpServletRequest httpRequest)
        {
            super(httpRequest);
            _httpServletRequest = httpRequest;
            Objects.requireNonNull(_servletPathMapping);
        }

        @Override
        public DispatcherType getDispatcherType()
        {
            return DispatcherType.ASYNC;
        }

        @Override
        public String getPathInfo()
        {
            // TODO what about a 404 dispatch?
            return Objects.requireNonNull(_servletPathMapping).getPathInfo();
        }

        @Override
        public String getServletPath()
        {
            // TODO what about a 404 dispatch?
            return Objects.requireNonNull(_servletPathMapping).getServletPath();
        }

        @Override
        public HttpServletMapping getHttpServletMapping()
        {
            // TODO what about a 404 dispatch?
            return Objects.requireNonNull(_servletPathMapping);
        }

        @Override
        public String getRequestURI()
        {
            return _uri == null ? null : _uri.getPath();
        }

        @Override
        public StringBuffer getRequestURL()
        {
            return _uri == null ? super.getRequestURL() :  new StringBuffer(HttpURI.build(_uri).query(null).scheme(super.getScheme()).host(super.getServerName()).port(super.getServerPort()).asString());
        }

        @Override
        public Object getAttribute(String name)
        {
            return switch (name)
            {
                case AsyncContextState.ASYNC_REQUEST_URI -> _httpServletRequest.getRequestURI();
                case AsyncContextState.ASYNC_CONTEXT_PATH -> _httpServletRequest.getContextPath();
                case AsyncContextState.ASYNC_MAPPING -> _httpServletRequest.getHttpServletMapping();
                case AsyncContextState.ASYNC_PATH_INFO -> _httpServletRequest.getPathInfo();
                case AsyncContextState.ASYNC_SERVLET_PATH -> _httpServletRequest.getServletPath();
                case AsyncContextState.ASYNC_QUERY_STRING -> _httpServletRequest.getQueryString();
                default -> super.getAttribute(name);
            };
        }

        @Override
        public Enumeration<String> getAttributeNames()
        {
            ArrayList<String> names = new ArrayList<>(Collections.list(super.getAttributeNames()));
            names.add(AsyncContextState.ASYNC_REQUEST_URI);
            names.add(AsyncContextState.ASYNC_SERVLET_PATH);
            names.add(AsyncContextState.ASYNC_PATH_INFO);
            names.add(AsyncContextState.ASYNC_CONTEXT_PATH);
            names.add(AsyncContextState.ASYNC_MAPPING);
            names.add(AsyncContextState.ASYNC_QUERY_STRING);
            return Collections.enumeration(names);
        }
    }

    private class ErrorRequest extends ParameterRequestWrapper
    {
        private final HttpServletRequest _httpServletRequest;

        public ErrorRequest(HttpServletRequest httpRequest)
        {
            super(httpRequest);
            _httpServletRequest = httpRequest;
        }

        @Override
        public DispatcherType getDispatcherType()
        {
            return DispatcherType.ERROR;
        }

        @Override
        public String getPathInfo()
        {
            return _servletPathMapping.getPathInfo();
        }

        @Override
        public String getServletPath()
        {
            return _servletPathMapping.getServletPath();
        }

        @Override
        public HttpServletMapping getHttpServletMapping()
        {
            return _servletPathMapping;
        }

        @Override
        public String getRequestURI()
        {
            return _uri.getPath();
        }

        @Override
        public StringBuffer getRequestURL()
        {
            return new StringBuffer(HttpURI.build(_uri)
                .scheme(getScheme())
                .host(getServerName())
                .port(getServerPort())
                .asString());
        }
    }

    @Override
    public String toString()
    {
        return String.format("Dispatcher@0x%x{%s,%s}", hashCode(), _named, _uri);
    }
}
