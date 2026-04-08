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

package org.eclipse.jetty.ee11.servlet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    private final Map<String, String> _errorPages = new HashMap<>(); // code or exception to URL
    private final List<ErrorCodeRange> _errorPageList = new ArrayList<>(); // list of ErrorCode by range
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
    public void prepare(ErrorPage errorPage, HttpServletRequest request, HttpServletResponse response)
    {
        if (errorPage.error() instanceof ServletException && _unwrapServletException)
        {
            Throwable unwrapped = unwrapServletException(errorPage.error(), errorPage.matchedClass());
            if (unwrapped != null)
            {
                request.setAttribute(org.eclipse.jetty.server.handler.ErrorHandler.ERROR_EXCEPTION, unwrapped);
                request.setAttribute(Dispatcher.ERROR_EXCEPTION_TYPE, unwrapped.getClass());
            }
        }
    }

    @Override
    public ErrorPage getErrorPage(Integer errorStatusCode, Throwable error)
    {
        String errorPage;

        // Walk the cause hierarchy
        Throwable cause = error;
        while (cause != null)
        {
            Class<?> exClass = cause.getClass();

            while (exClass != null)
            {
                errorPage = _errorPages.get(exClass.getName());
                if (errorPage != null)
                    return new ErrorPage(errorPage, PageLookupTechnique.THROWABLE, error, cause, exClass);
                exClass = exClass.getSuperclass();
            }

            cause = (cause instanceof ServletException se) ? se.getRootCause() : cause.getCause();
        }

        // look for an exact code match
        if (errorStatusCode != null)
        {
            errorPage = _errorPages.get(Integer.toString(errorStatusCode));
            if (errorPage != null)
                return new ErrorPage(errorPage, PageLookupTechnique.STATUS_CODE, error, error, null);

            // look for an error code range match.
            for (ErrorCodeRange errCode : _errorPageList)
            {
                if (errCode.isInRange(errorStatusCode))
                    return new ErrorPage(errCode.getUri(), PageLookupTechnique.STATUS_CODE, error, error, null);
            }
        }

        // Try servlet 3.x global error page.
        errorPage = _errorPages.get(GLOBAL_ERROR_PAGE);
        if (errorPage != null)
            return new ErrorPage(errorPage, PageLookupTechnique.GLOBAL, error, error, null);

        return null;
    }

    /**
     *
     * @param t the initial exception
     * @param matchedThrowable the class we found matching the error page (can be null)
     * @return the first non {@link ServletException} from root cause chain
     */
    private Throwable unwrapServletException(Throwable t, Class<?> matchedThrowable)
    {
        if (matchedThrowable != null && t.getClass() == matchedThrowable)
            return t;
        if (t instanceof ServletException && t.getCause() != null)
        {
            return unwrapServletException(t.getCause(), matchedThrowable);
        }
        return t;
    }

    public Map<String, String> getErrorPages()
    {
        return _errorPages;
    }

    /**
     * Set a map of Exception class names or error codes as a string to URI string.
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
