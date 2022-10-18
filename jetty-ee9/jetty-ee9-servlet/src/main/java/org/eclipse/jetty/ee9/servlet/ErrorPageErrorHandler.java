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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jetty.ee9.nested.ContextHandler;
import org.eclipse.jetty.ee9.nested.Dispatcher;
import org.eclipse.jetty.ee9.nested.ErrorHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An ErrorHandler that maps exceptions and status codes to URIs for dispatch using
 * the internal ERROR style of dispatch.
 */
public class ErrorPageErrorHandler extends ErrorHandler implements ErrorHandler.ErrorPageMapper
{
    public static final String GLOBAL_ERROR_PAGE = "org.eclipse.jetty.server.error_page.global";
    private static final Logger LOG = LoggerFactory.getLogger(ErrorPageErrorHandler.class);

    private enum PageLookupTechnique
    {
        THROWABLE, STATUS_CODE, GLOBAL
    }

    private final Map<String, String> _errorPages = new HashMap<>(); // code or exception to URL
    private final List<ErrorCodeRange> _errorPageList = new ArrayList<>(); // list of ErrorCode by range
    protected ServletContext _servletContext;
    private boolean _unwrapServletException = true;

    /**
     * @return True if ServletException is unwrapped for {@link Dispatcher#ERROR_EXCEPTION}
     */
    public boolean isUnwrapServletException()
    {
        return _unwrapServletException;
    }

    /**
     * @param unwrapServletException True if ServletException should be unwrapped for {@link Dispatcher#ERROR_EXCEPTION}
     */
    public void setUnwrapServletException(boolean unwrapServletException)
    {
        _unwrapServletException = unwrapServletException;
    }

    @Override
    public String getErrorPage(HttpServletRequest request)
    {
        String errorPage = null;

        PageLookupTechnique pageSource = null;

        Class<?> matchedThrowable = null;
        Throwable error = (Throwable)request.getAttribute(Dispatcher.ERROR_EXCEPTION);
        Throwable cause = error;

        // Walk the cause hierarchy
        while (errorPage == null && cause != null)
        {
            pageSource = PageLookupTechnique.THROWABLE;

            Class<?> exClass = cause.getClass();
            errorPage = _errorPages.get(exClass.getName());

            // walk the inheritance hierarchy
            while (errorPage == null)
            {
                exClass = exClass.getSuperclass();
                if (exClass == null)
                    break;
                errorPage = _errorPages.get(exClass.getName());
            }

            if (errorPage != null)
                matchedThrowable = exClass;

            cause = (cause instanceof ServletException) ? ((ServletException)cause).getRootCause() : null;
        }

        if (error instanceof ServletException && _unwrapServletException)
        {
            Throwable unwrapped = getFirstNonServletException(error);
            if (unwrapped != null)
            {
                request.setAttribute(Dispatcher.ERROR_EXCEPTION, unwrapped);
                request.setAttribute(Dispatcher.ERROR_EXCEPTION_TYPE, unwrapped.getClass());
            }
        }

        Integer errorStatusCode = null;

        if (errorPage == null)
        {
            pageSource = PageLookupTechnique.STATUS_CODE;

            // look for an exact code match
            errorStatusCode = (Integer)request.getAttribute(Dispatcher.ERROR_STATUS_CODE);
            if (errorStatusCode != null)
            {
                errorPage = _errorPages.get(Integer.toString(errorStatusCode));

                // if still not found
                if (errorPage == null)
                {
                    // look for an error code range match.
                    for (ErrorCodeRange errCode : _errorPageList)
                    {
                        if (errCode.isInRange(errorStatusCode))
                        {
                            errorPage = errCode.getUri();
                            break;
                        }
                    }
                }
            }
        }

        // Try servlet 3.x global error page.
        if (errorPage == null)
        {
            pageSource = PageLookupTechnique.GLOBAL;
            errorPage = _errorPages.get(GLOBAL_ERROR_PAGE);
        }

        if (LOG.isDebugEnabled())
        {
            StringBuilder dbg = new StringBuilder();
            dbg.append("getErrorPage(");
            dbg.append(request.getMethod()).append(' ');
            dbg.append(request.getRequestURI());
            dbg.append(") => error_page=").append(errorPage);
            switch (pageSource)
            {
                case THROWABLE:
                    dbg.append(" (using matched Throwable ");
                    dbg.append(matchedThrowable.getName());
                    dbg.append(" / actually thrown as ");
                    Throwable originalThrowable = (Throwable)request.getAttribute(Dispatcher.ERROR_EXCEPTION);
                    dbg.append(originalThrowable.getClass().getName());
                    dbg.append(')');
                    LOG.debug(dbg.toString(), cause);
                    break;
                case STATUS_CODE:
                    dbg.append(" (from status code ");
                    dbg.append(errorStatusCode);
                    dbg.append(')');
                    LOG.debug(dbg.toString());
                    break;
                case GLOBAL:
                    dbg.append(" (from global default)");
                    LOG.debug(dbg.toString());
                    break;
                default:
                    throw new IllegalStateException(pageSource.toString());
            }
        }

        return errorPage;
    }

    /**
     *
     * @param t the initial exception
     * @return the first non {@link ServletException} from root cause chain
     */
    private Throwable getFirstNonServletException(Throwable t)
    {
        if (t instanceof ServletException && t.getCause() != null)
        {
            return getFirstNonServletException(t.getCause());
        }
        return t;
    }

    public Map<String, String> getErrorPages()
    {
        return _errorPages;
    }

    /**
     * @param errorPages a map of Exception class names or error codes as a string to URI string
     */
    public void setErrorPages(Map<String, String> errorPages)
    {
        _errorPages.clear();
        if (errorPages != null)
            _errorPages.putAll(errorPages);
    }

    /**
     * Adds ErrorPage mapping for an exception class.
     * This method is called as a result of an exception-type element in a web.xml file
     * or may be called directly
     *
     * @param exception The exception
     * @param uri The URI of the error page.
     */
    public void addErrorPage(Class<? extends Throwable> exception, String uri)
    {
        _errorPages.put(exception.getName(), uri);
    }

    /**
     * Adds ErrorPage mapping for an exception class.
     * This method is called as a result of an exception-type element in a web.xml file
     * or may be called directly
     *
     * @param exceptionClassName The exception
     * @param uri The URI of the error page.
     */
    public void addErrorPage(String exceptionClassName, String uri)
    {
        _errorPages.put(exceptionClassName, uri);
    }

    /**
     * Adds ErrorPage mapping for a status code.
     * This method is called as a result of an error-code element in a web.xml file
     * or may be called directly.
     *
     * @param code The HTTP status code to match
     * @param uri The URI of the error page.
     */
    public void addErrorPage(int code, String uri)
    {
        _errorPages.put(Integer.toString(code), uri);
    }

    /**
     * Adds ErrorPage mapping for a status code range.
     * This method is not available from web.xml and must be called directly.
     *
     * @param from The lowest matching status code
     * @param to The highest matching status code
     * @param uri The URI of the error page.
     */
    public void addErrorPage(int from, int to, String uri)
    {
        _errorPageList.add(new ErrorCodeRange(from, to, uri));
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        _servletContext = ContextHandler.getCurrentContext();
    }

    private static class ErrorCodeRange
    {
        private final int _from;
        private final int _to;
        private final String _uri;

        ErrorCodeRange(int from, int to, String uri)
            throws IllegalArgumentException
        {
            if (from > to)
                throw new IllegalArgumentException("from>to");

            _from = from;
            _to = to;
            _uri = uri;
        }

        boolean isInRange(int value)
        {
            return _from <= value && value <= _to;
        }

        String getUri()
        {
            return _uri;
        }

        @Override
        public String toString()
        {
            return "from: " + _from + ",to: " + _to + ",uri: " + _uri;
        }
    }
}
