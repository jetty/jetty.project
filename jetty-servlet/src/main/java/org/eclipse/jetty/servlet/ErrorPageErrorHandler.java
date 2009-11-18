// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;

/** Error Page Error Handler
 * 
 * An ErrorHandler that maps exceptions and status codes to URIs for dispatch using
 * the internal ERROR style of dispatch.
 * 
 *
 */
public class ErrorPageErrorHandler extends ErrorHandler
{
    public final static String ERROR_PAGE="org.eclipse.jetty.server.error_page";
    
    protected ServletContext _servletContext;
    protected Map _errorPages; // code or exception to URL
    protected List _errorPageList; // list of ErrorCode by range 

    /* ------------------------------------------------------------ */
    /**
     * @param context
     */
    public ErrorPageErrorHandler()
    {}

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.jetty.server.handler.ErrorHandler#handle(java.lang.String, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, int)
     */
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        String method = request.getMethod();
        if(!method.equals(HttpMethods.GET) && !method.equals(HttpMethods.POST) && !method.equals(HttpMethods.HEAD))
        {
            HttpConnection.getCurrentConnection().getRequest().setHandled(true);
            return;
        }
        if (_errorPages!=null)
        {
            String error_page= null;
            Class exClass= (Class)request.getAttribute(Dispatcher.ERROR_EXCEPTION_TYPE);
            
            if (ServletException.class.equals(exClass))
            {
                error_page= (String)_errorPages.get(exClass.getName());
                if (error_page == null)
                {
                    Throwable th= (Throwable)request.getAttribute(Dispatcher.ERROR_EXCEPTION);
                    while (th instanceof ServletException)
                        th= ((ServletException)th).getRootCause();
                    if (th != null)
                        exClass= th.getClass();
                }
            }
            
            while (error_page == null && exClass != null )
            {
                error_page= (String)_errorPages.get(exClass.getName());
                exClass= exClass.getSuperclass();
            }
            
            if (error_page == null)
            {
                // look for an exact code match
                Integer code=(Integer)request.getAttribute(Dispatcher.ERROR_STATUS_CODE);
                if (code!=null)
                {
                    error_page= (String)_errorPages.get(TypeUtil.toString(code.intValue()));

                    // if still not found
                    if ((error_page == null) && (_errorPageList != null))
                    {
                        // look for an error code range match.
                        for (int i = 0; i < _errorPageList.size(); i++)
                        {
                            ErrorCodeRange errCode = (ErrorCodeRange) _errorPageList.get(i);
                            if (errCode.isInRange(code.intValue()))
                            {
                                error_page = errCode.getUri();
                                break;
                            }
                        }
                    }
                }
            }
            
            if (error_page!=null)
            {
                String old_error_page=(String)request.getAttribute(ERROR_PAGE);
                if (old_error_page==null || !old_error_page.equals(error_page))
                {
                    request.setAttribute(ERROR_PAGE, error_page);
                    
                    Dispatcher dispatcher = (Dispatcher) _servletContext.getRequestDispatcher(error_page);
                    try
                    {
                        if(dispatcher!=null)
                        {    
                            dispatcher.error(request, response);
                            return;
                        }
                        else
                        {
                            Log.warn("No error page "+error_page);
                        }
                    }
                    catch (ServletException e)
                    {
                        Log.warn(Log.EXCEPTION, e);
                        return;
                    }
                }
            }
        }
        
        super.handle(target, baseRequest, request, response);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the errorPages.
     */
    public Map getErrorPages()
    {
        return _errorPages;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param errorPages The errorPages to set. A map of Exception class name  or error code as a string to URI string
     */
    public void setErrorPages(Map errorPages)
    {
        _errorPages = errorPages;
    }

    /* ------------------------------------------------------------ */
    /** Add Error Page mapping for an exception class
     * This method is called as a result of an exception-type element in a web.xml file
     * or may be called directly
     * @param code The class (or superclass) of the matching exceptions
     * @param uri The URI of the error page.
     */
    public void addErrorPage(Class exception,String uri)
    {
        if (_errorPages==null)
            _errorPages=new HashMap();
        _errorPages.put(exception.getName(),uri);
    }
    
    /* ------------------------------------------------------------ */
    /** Add Error Page mapping for a status code.
     * This method is called as a result of an error-code element in a web.xml file
     * or may be called directly
     * @param code The HTTP status code to match
     * @param uri The URI of the error page.
     */
    public void addErrorPage(int code,String uri)
    {
        if (_errorPages==null)
            _errorPages=new HashMap();
        _errorPages.put(TypeUtil.toString(code),uri);
    }
    
    /* ------------------------------------------------------------ */
    /** Add Error Page mapping for a status code range.
     * This method is not available from web.xml and must be called
     * directly.
     * @param from The lowest matching status code
     * @param to The highest matching status code
     * @param uri The URI of the error page.
     */
    public void addErrorPage(int from, int to, String uri)
    {
        if (_errorPageList == null)
        {
            _errorPageList = new ArrayList();
        }
        _errorPageList.add(new ErrorCodeRange(from, to, uri));
    }

    /* ------------------------------------------------------------ */
    protected void doStart() throws Exception
    {
        super.doStart();
        _servletContext=ContextHandler.getCurrentContext();
    }

    /* ------------------------------------------------------------ */
    protected void doStop() throws Exception
    {
        // TODO Auto-generated method stub
        super.doStop();
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class ErrorCodeRange
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
            if ((value >= _from) && (value <= _to))
            {
                return true;
            }
            
            return false;
        }
        
        String getUri()
        {
            return _uri;
        }
        
        public String toString()
        {
            return "from: " + _from + ",to: " + _to + ",uri: " + _uri;
        }
    }    
}
