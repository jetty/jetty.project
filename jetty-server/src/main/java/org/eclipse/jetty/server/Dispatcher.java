//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.UrlEncoded;

/* ------------------------------------------------------------ */
/** Servlet RequestDispatcher.
 * 
 * 
 */
public class Dispatcher implements RequestDispatcher
{
    /** Dispatch include attribute names */
    public final static String __INCLUDE_PREFIX="javax.servlet.include.";

    /** Dispatch include attribute names */
    public final static String __FORWARD_PREFIX="javax.servlet.forward.";

    /** JSP attributes */
    public final static String __JSP_FILE="org.apache.catalina.jsp_file";

    /* ------------------------------------------------------------ */
    private final ContextHandler _contextHandler;
    private final String _uri;
    private final String _path;
    private final String _dQuery;
    private final String _named;
    
    /* ------------------------------------------------------------ */
    /**
     * @param contextHandler
     * @param uri
     * @param pathInContext
     * @param query
     */
    public Dispatcher(ContextHandler contextHandler, String uri, String pathInContext, String query)
    {
        _contextHandler=contextHandler;
        _uri=uri;
        _path=pathInContext;
        _dQuery=query;
        _named=null;
    }


    /* ------------------------------------------------------------ */
    /** Constructor. 
     * @param contextHandler
     * @param name
     */
    public Dispatcher(ContextHandler contextHandler,String name)
        throws IllegalStateException
    {
        _contextHandler=contextHandler;
        _named=name;
        _uri=null;
        _path=null;
        _dQuery=null;
    }
    
    /* ------------------------------------------------------------ */
    /* 
     * @see javax.servlet.RequestDispatcher#forward(javax.servlet.ServletRequest, javax.servlet.ServletResponse)
     */
    public void forward(ServletRequest request, ServletResponse response) throws ServletException, IOException
    {
        forward(request, response, DispatcherType.FORWARD);
    }
    
    /* ------------------------------------------------------------ */
    /* 
     * @see javax.servlet.RequestDispatcher#forward(javax.servlet.ServletRequest, javax.servlet.ServletResponse)
     */
    public void error(ServletRequest request, ServletResponse response) throws ServletException, IOException
    {
        forward(request, response, DispatcherType.ERROR);
    }
    
    /* ------------------------------------------------------------ */
    /* 
     * @see javax.servlet.RequestDispatcher#include(javax.servlet.ServletRequest, javax.servlet.ServletResponse)
     */
    public void include(ServletRequest request, ServletResponse response) throws ServletException, IOException
    {
        Request baseRequest=(request instanceof Request)?((Request)request):AbstractHttpConnection.getCurrentConnection().getRequest();
      
        
        if (!(request instanceof HttpServletRequest))
            request = new ServletRequestHttpWrapper(request);
        if (!(response instanceof HttpServletResponse))
            response = new ServletResponseHttpWrapper(response);
            
        
        // TODO - allow stream or writer????
        
        final DispatcherType old_type = baseRequest.getDispatcherType();
        final Attributes old_attr=baseRequest.getAttributes();
        MultiMap old_params=baseRequest.getParameters();
        try
        {
            baseRequest.setDispatcherType(DispatcherType.INCLUDE);
            baseRequest.getConnection().include();
            if (_named!=null)
                _contextHandler.handle(_named,baseRequest, (HttpServletRequest)request, (HttpServletResponse)response);
            else 
            {
                String query=_dQuery;
                
                if (query!=null)
                {
                    // force parameter extraction
                    if (old_params==null)
                    {
                        baseRequest.extractParameters();
                        old_params=baseRequest.getParameters();
                    }
                    
                    MultiMap parameters=new MultiMap();
                    UrlEncoded.decodeTo(query,parameters,baseRequest.getCharacterEncoding());
                    
                    if (old_params!=null && old_params.size()>0)
                    {
                        // Merge parameters.
                        Iterator iter = old_params.entrySet().iterator();
                        while (iter.hasNext())
                        {
                            Map.Entry entry = (Map.Entry)iter.next();
                            String name=(String)entry.getKey();
                            Object values=entry.getValue();
                            for (int i=0;i<LazyList.size(values);i++)
                                parameters.add(name, LazyList.get(values, i));
                        }
                    }
                    baseRequest.setParameters(parameters);
                }
                
                IncludeAttributes attr = new IncludeAttributes(old_attr); 
                
                attr._requestURI=_uri;
                attr._contextPath=_contextHandler.getContextPath();
                attr._servletPath=null; // set by ServletHandler
                attr._pathInfo=_path;
                attr._query=query;
                
                baseRequest.setAttributes(attr);
                
                _contextHandler.handle(_path,baseRequest, (HttpServletRequest)request, (HttpServletResponse)response);
            }
        }
        finally
        {
            baseRequest.setAttributes(old_attr);
            baseRequest.getConnection().included();
            baseRequest.setParameters(old_params);
            baseRequest.setDispatcherType(old_type);
        }
    }

    
    /* ------------------------------------------------------------ */
    /* 
     * @see javax.servlet.RequestDispatcher#forward(javax.servlet.ServletRequest, javax.servlet.ServletResponse)
     */
    protected void forward(ServletRequest request, ServletResponse response, DispatcherType dispatch) throws ServletException, IOException
    {
        Request baseRequest=(request instanceof Request)?((Request)request):AbstractHttpConnection.getCurrentConnection().getRequest();
        Response base_response=baseRequest.getResponse();
        response.resetBuffer();
        base_response.fwdReset();
       

        if (!(request instanceof HttpServletRequest))
            request = new ServletRequestHttpWrapper(request);
        if (!(response instanceof HttpServletResponse))
            response = new ServletResponseHttpWrapper(response);
        
        final boolean old_handled=baseRequest.isHandled();
        final String old_uri=baseRequest.getRequestURI();
        final String old_context_path=baseRequest.getContextPath();
        final String old_servlet_path=baseRequest.getServletPath();
        final String old_path_info=baseRequest.getPathInfo();
        final String old_query=baseRequest.getQueryString();
        final Attributes old_attr=baseRequest.getAttributes();
        final DispatcherType old_type=baseRequest.getDispatcherType();
        MultiMap<String> old_params=baseRequest.getParameters();
        
        try
        {
            baseRequest.setHandled(false);
            baseRequest.setDispatcherType(dispatch);
            
            if (_named!=null)
                _contextHandler.handle(_named,baseRequest, (HttpServletRequest)request, (HttpServletResponse)response);
            else 
            {
                
                // process any query string from the dispatch URL
                String query=_dQuery;
                if (query!=null)
                {
                    // force parameter extraction
                    if (old_params==null)
                    {
                        baseRequest.extractParameters();
                        old_params=baseRequest.getParameters();
                    }
                    
                    baseRequest.mergeQueryString(query);
                }
                
                ForwardAttributes attr = new ForwardAttributes(old_attr); 
                
                //If we have already been forwarded previously, then keep using the established 
                //original value. Otherwise, this is the first forward and we need to establish the values.
                //Note: the established value on the original request for pathInfo and
                //for queryString is allowed to be null, but cannot be null for the other values.
                if (old_attr.getAttribute(FORWARD_REQUEST_URI) != null)
                {
                    attr._pathInfo=(String)old_attr.getAttribute(FORWARD_PATH_INFO);
                    attr._query=(String)old_attr.getAttribute(FORWARD_QUERY_STRING);
                    attr._requestURI=(String)old_attr.getAttribute(FORWARD_REQUEST_URI);
                    attr._contextPath=(String)old_attr.getAttribute(FORWARD_CONTEXT_PATH);
                    attr._servletPath=(String)old_attr.getAttribute(FORWARD_SERVLET_PATH);
                }
                else
                {
                    attr._pathInfo=old_path_info;
                    attr._query=old_query;
                    attr._requestURI=old_uri;
                    attr._contextPath=old_context_path;
                    attr._servletPath=old_servlet_path;
                }     
                
                baseRequest.setRequestURI(_uri);
                baseRequest.setContextPath(_contextHandler.getContextPath());
                baseRequest.setServletPath(null);
                baseRequest.setPathInfo(_uri);
                baseRequest.setAttributes(attr);
                
                _contextHandler.handle(_path,baseRequest, (HttpServletRequest)request, (HttpServletResponse)response);
                
                if (!baseRequest.getAsyncContinuation().isAsyncStarted())
                    commitResponse(response,baseRequest);
            }
        }
        finally
        {
            baseRequest.setHandled(old_handled);
            baseRequest.setRequestURI(old_uri);
            baseRequest.setContextPath(old_context_path);
            baseRequest.setServletPath(old_servlet_path);
            baseRequest.setPathInfo(old_path_info);
            baseRequest.setAttributes(old_attr);
            baseRequest.setParameters(old_params);
            baseRequest.setQueryString(old_query);
            baseRequest.setDispatcherType(old_type);
        }
    }


    /* ------------------------------------------------------------ */
    private void commitResponse(ServletResponse response, Request baseRequest) throws IOException
    {
        if (baseRequest.getResponse().isWriting())
        {
            try
            {
                response.getWriter().close();
            }
            catch (IllegalStateException e)
            {
                response.getOutputStream().close();
            }
        }
        else
        {
            try
            {
                response.getOutputStream().close();
            }
            catch (IllegalStateException e)
            {
                response.getWriter().close();
            }
        }
    }


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class ForwardAttributes implements Attributes
    {
        final Attributes _attr;
        
        String _requestURI;
        String _contextPath;
        String _servletPath;
        String _pathInfo;
        String _query;
        
        ForwardAttributes(Attributes attributes)
        {
            _attr=attributes;
        }
        
        /* ------------------------------------------------------------ */
        public Object getAttribute(String key)
        {
            if (Dispatcher.this._named==null)
            {
                if (key.equals(FORWARD_PATH_INFO))    
                    return _pathInfo;
                if (key.equals(FORWARD_REQUEST_URI))  
                    return _requestURI;
                if (key.equals(FORWARD_SERVLET_PATH)) 
                    return _servletPath;
                if (key.equals(FORWARD_CONTEXT_PATH)) 
                    return _contextPath;
                if (key.equals(FORWARD_QUERY_STRING)) 
                    return _query;
            }
            
            if (key.startsWith(__INCLUDE_PREFIX))
                return null;
            
            return _attr.getAttribute(key);
        }
        
        /* ------------------------------------------------------------ */
        public Enumeration getAttributeNames()
        {
            HashSet set=new HashSet();
            Enumeration e=_attr.getAttributeNames();
            while(e.hasMoreElements())
            {
                String name=(String)e.nextElement();
                if (!name.startsWith(__INCLUDE_PREFIX) &&
                    !name.startsWith(__FORWARD_PREFIX))
                    set.add(name);
            }
            
            if (_named==null)
            {
                if (_pathInfo!=null)
                    set.add(FORWARD_PATH_INFO);
                else
                    set.remove(FORWARD_PATH_INFO);
                set.add(FORWARD_REQUEST_URI);
                set.add(FORWARD_SERVLET_PATH);
                set.add(FORWARD_CONTEXT_PATH);
                if (_query!=null)
                    set.add(FORWARD_QUERY_STRING);
                else
                    set.remove(FORWARD_QUERY_STRING);
            }

            return Collections.enumeration(set);
        }
        
        /* ------------------------------------------------------------ */
        public void setAttribute(String key, Object value)
        {
            if (_named==null && key.startsWith("javax.servlet."))
            {
                if (key.equals(FORWARD_PATH_INFO))         
                    _pathInfo=(String)value;
                else if (key.equals(FORWARD_REQUEST_URI))  
                    _requestURI=(String)value;
                else if (key.equals(FORWARD_SERVLET_PATH)) 
                    _servletPath=(String)value;
                else if (key.equals(FORWARD_CONTEXT_PATH)) 
                    _contextPath=(String)value;
                else if (key.equals(FORWARD_QUERY_STRING)) 
                    _query=(String)value;
                
                else if (value==null)
                    _attr.removeAttribute(key);
                else
                    _attr.setAttribute(key,value); 
            }
            else if (value==null)
                _attr.removeAttribute(key);
            else
                _attr.setAttribute(key,value);
        }
        
        /* ------------------------------------------------------------ */
        @Override
        public String toString() 
        {
            return "FORWARD+"+_attr.toString();
        }

        /* ------------------------------------------------------------ */
        public void clearAttributes()
        {
            throw new IllegalStateException();
        }

        /* ------------------------------------------------------------ */
        public void removeAttribute(String name)
        {
            setAttribute(name,null);
        }
    }

    /* ------------------------------------------------------------ */
    private class IncludeAttributes implements Attributes
    {
        final Attributes _attr;
        
        String _requestURI;
        String _contextPath;
        String _servletPath;
        String _pathInfo;
        String _query;
        
        IncludeAttributes(Attributes attributes)
        {
            _attr=attributes;
        }
        
        /* ------------------------------------------------------------ */
        /* ------------------------------------------------------------ */
        /* ------------------------------------------------------------ */
        public Object getAttribute(String key)
        {
            if (Dispatcher.this._named==null)
            {
                if (key.equals(INCLUDE_PATH_INFO))    return _pathInfo;
                if (key.equals(INCLUDE_SERVLET_PATH)) return _servletPath;
                if (key.equals(INCLUDE_CONTEXT_PATH)) return _contextPath;
                if (key.equals(INCLUDE_QUERY_STRING)) return _query;
                if (key.equals(INCLUDE_REQUEST_URI))  return _requestURI;
            }
            else if (key.startsWith(__INCLUDE_PREFIX)) 
                    return null;
            
            
            return _attr.getAttribute(key);
        }
        
        /* ------------------------------------------------------------ */
        public Enumeration getAttributeNames()
        {
            HashSet set=new HashSet();
            Enumeration e=_attr.getAttributeNames();
            while(e.hasMoreElements())
            {
                String name=(String)e.nextElement();
                if (!name.startsWith(__INCLUDE_PREFIX))
                    set.add(name);
            }
            
            if (_named==null)
            {
                if (_pathInfo!=null)
                    set.add(INCLUDE_PATH_INFO);
                else
                    set.remove(INCLUDE_PATH_INFO);
                set.add(INCLUDE_REQUEST_URI);
                set.add(INCLUDE_SERVLET_PATH);
                set.add(INCLUDE_CONTEXT_PATH);
                if (_query!=null)
                    set.add(INCLUDE_QUERY_STRING);
                else
                    set.remove(INCLUDE_QUERY_STRING);
            }
            
            return Collections.enumeration(set);
        }
        
        /* ------------------------------------------------------------ */
        public void setAttribute(String key, Object value)
        {
            if (_named==null && key.startsWith("javax.servlet."))
            {
                if (key.equals(INCLUDE_PATH_INFO))         _pathInfo=(String)value;
                else if (key.equals(INCLUDE_REQUEST_URI))  _requestURI=(String)value;
                else if (key.equals(INCLUDE_SERVLET_PATH)) _servletPath=(String)value;
                else if (key.equals(INCLUDE_CONTEXT_PATH)) _contextPath=(String)value;
                else if (key.equals(INCLUDE_QUERY_STRING)) _query=(String)value;
                else if (value==null)
                    _attr.removeAttribute(key);
                else
                    _attr.setAttribute(key,value); 
            }
            else if (value==null)
                _attr.removeAttribute(key);
            else
                _attr.setAttribute(key,value);
        }
        
        /* ------------------------------------------------------------ */
        @Override
        public String toString() 
        {
            return "INCLUDE+"+_attr.toString();
        }

        /* ------------------------------------------------------------ */
        public void clearAttributes()
        {
            throw new IllegalStateException();
        }

        /* ------------------------------------------------------------ */
        public void removeAttribute(String name)
        {
            setAttribute(name,null);
        }
    }
}
