//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.servlet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * An ErrorHandler that maps exceptions and status codes to URIs for dispatch using
 * the internal ERROR style of dispatch.
 */
public class ErrorPageErrorHandler extends ErrorHandler implements ErrorHandler.ErrorPageMapper
{
    public final static String GLOBAL_ERROR_PAGE = "org.eclipse.jetty.server.error_page.global";
    private static final Logger LOG = Log.getLogger(ErrorPageErrorHandler.class);

    private enum PageLookupTechnique
    {
        THROWABLE, STATUS_CODE, GLOBAL
    }

    protected ServletContext _servletContext;
    private final Map<String, String> _errorPages = new HashMap<>(); // code or exception to URL
    private final List<ErrorCodeRange> _errorPageList = new ArrayList<>(); // list of ErrorCode by range

    @Override
    public String getErrorPage(HttpServletRequest request)
    {
        String error_page = null;

        PageLookupTechnique pageSource = null;

        Class<?> matchedThrowable = null;
        Throwable th = (Throwable)request.getAttribute(Dispatcher.ERROR_EXCEPTION);

        // Walk the cause hierarchy
        while (error_page == null && th != null)
        {
            pageSource = PageLookupTechnique.THROWABLE;

            Class<?> exClass = th.getClass();
            error_page = _errorPages.get(exClass.getName());

            // walk the inheritance hierarchy
            while (error_page == null)
            {
                exClass = exClass.getSuperclass();
                if (exClass == null)
                    break;
                error_page = _errorPages.get(exClass.getName());
            }

            if (error_page != null)
                matchedThrowable = exClass;

            th = (th instanceof ServletException) ? ((ServletException)th).getRootCause() : null;
        }

        Integer errorStatusCode = null;

        if (error_page == null)
        {
            pageSource = PageLookupTechnique.STATUS_CODE;

            // look for an exact code match
            errorStatusCode = (Integer)request.getAttribute(Dispatcher.ERROR_STATUS_CODE);
            if (errorStatusCode != null)
            {
                error_page = _errorPages.get(Integer.toString(errorStatusCode));

                // if still not found
                if (error_page == null)
                {
                    // look for an error code range match.
                    for (ErrorCodeRange errCode : _errorPageList)
                    {
                        if (errCode.isInRange(errorStatusCode))
                        {
                            error_page = errCode.getUri();
                            break;
                        }
                    }
                }
            }
        }

        // Try servlet 3.x global error page.
        if (error_page == null)
        {
            pageSource = PageLookupTechnique.GLOBAL;
            error_page = _errorPages.get(GLOBAL_ERROR_PAGE);
        }

        if (LOG.isDebugEnabled())
        {
            StringBuilder dbg = new StringBuilder();
            dbg.append("getErrorPage(");
            dbg.append(request.getMethod()).append(' ');
            dbg.append(request.getRequestURI());
            dbg.append(") => error_page=").append(error_page);
            switch (pageSource)
            {
                case THROWABLE:
                    dbg.append(" (using matched Throwable ");
                    dbg.append(matchedThrowable.getName());
                    dbg.append(" / actually thrown as ");
                    Throwable originalThrowable = (Throwable)request.getAttribute(Dispatcher.ERROR_EXCEPTION);
                    dbg.append(originalThrowable.getClass().getName());
                    dbg.append(')');
                    LOG.debug(dbg.toString(), th);
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
            }
        }

        return error_page;
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
     * @param uri       The URI of the error page.
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
     * @param uri                The URI of the error page.
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
     * @param uri  The URI of the error page.
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
     * @param to   The highest matching status code
     * @param uri  The URI of the error page.
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
        private int _from;
        private int _to;
        private String _uri;

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
