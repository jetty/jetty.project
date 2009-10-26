// ========================================================================
// Copyright (c) 1999-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

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
    public static final String FORWARD_REQUEST_URI = "javax.servlet.forward.request_uri";
    public static final String FORWARD_CONTEXT_PATH = "javax.servlet.forward.context_path";
    public static final String FORWARD_PATH_INFO = "javax.servlet.forward.path_info";
    public static final String FORWARD_SERVLET_PATH = "javax.servlet.forward.servlet_path";
    public static final String FORWARD_QUERY_STRING = "javax.servlet.forward.query_string";
    public static final String INCLUDE_REQUEST_URI = "javax.servlet.include.request_uri";
    public static final String INCLUDE_CONTEXT_PATH = "javax.servlet.include.context_path";
    public static final String INCLUDE_PATH_INFO = "javax.servlet.include.path_info";
    public static final String INCLUDE_SERVLET_PATH = "javax.servlet.include.servlet_path";
    public static final String INCLUDE_QUERY_STRING = "javax.servlet.include.query_string";

    public static final String ERROR_EXCEPTION = "javax.servlet.error.exception";
    public static final String ERROR_EXCEPTION_TYPE = "javax.servlet.error.exception_type";
    public static final String ERROR_MESSAGE = "javax.servlet.error.message";
    public static final String ERROR_REQUEST_URI = "javax.servlet.error.request_uri";
    public static final String ERROR_SERVLET_NAME = "javax.servlet.error.servlet_name";
    public static final String ERROR_STATUS_CODE = "javax.servlet.error.status_code";

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
        Request baseRequest=(request instanceof Request)?((Request)request):HttpConnection.getCurrentConnection().getRequest();
        request.removeAttribute(__JSP_FILE); // TODO remove when glassfish 1044 is fixed
        
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
                    UrlEncoded.decodeTo(query,parameters,request.getCharacterEncoding());
                    
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
        Request baseRequest=(request instanceof Request)?((Request)request):HttpConnection.getCurrentConnection().getRequest();
        Response base_response=baseRequest.getResponse();
        response.resetBuffer();
        base_response.fwdReset();
        request.removeAttribute(__JSP_FILE); // TODO remove when glassfish 1044 is fixed
        
        final String old_uri=baseRequest.getRequestURI();
        final String old_context_path=baseRequest.getContextPath();
        final String old_servlet_path=baseRequest.getServletPath();
        final String old_path_info=baseRequest.getPathInfo();
        final String old_query=baseRequest.getQueryString();
        final Attributes old_attr=baseRequest.getAttributes();
        final DispatcherType old_type=baseRequest.getDispatcherType();
        MultiMap old_params=baseRequest.getParameters();
        
        try
        {
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
                    
                    // extract parameters from dispatch query
                    MultiMap parameters=new MultiMap();
                    UrlEncoded.decodeTo(query,parameters,request.getCharacterEncoding());
                 
                    boolean merge_old_query = false;

                    // Have we evaluated parameters
                    if( old_params == null )
                    {
                        // no - so force parameters to be evaluated
                        baseRequest.getParameterNames();
                        old_params = baseRequest.getParameters();
                    }
                    
                    // Are there any existing parameters?
                    if (old_params!=null && old_params.size()>0)
                    {
                        // Merge parameters; new parameters of the same name take precedence.
                        Iterator iter = old_params.entrySet().iterator();
                        while (iter.hasNext())
                        {
                            Map.Entry entry = (Map.Entry)iter.next();
                            String name=(String)entry.getKey();
                            
                            // If the names match, we will need to remake the query string
                            if (parameters.containsKey(name))
                                merge_old_query = true;

                            // Add the old values to the new parameter map
                            Object values=entry.getValue();
                            for (int i=0;i<LazyList.size(values);i++)
                                parameters.add(name, LazyList.get(values, i));
                        }
                    }
                    
                    if (old_query != null && old_query.length()>0)
                    {
                        if ( merge_old_query )
                        {
                            StringBuilder overridden_query_string = new StringBuilder();
                            MultiMap overridden_old_query = new MultiMap();
                            UrlEncoded.decodeTo(old_query,overridden_old_query,request.getCharacterEncoding());
    
                            MultiMap overridden_new_query = new MultiMap(); 
                            UrlEncoded.decodeTo(query,overridden_new_query,request.getCharacterEncoding());

                            Iterator iter = overridden_old_query.entrySet().iterator();
                            while (iter.hasNext())
                            {
                                Map.Entry entry = (Map.Entry)iter.next();
                                String name=(String)entry.getKey();
                                if(!overridden_new_query.containsKey(name))
                                {
                                    Object values=entry.getValue();
                                    for (int i=0;i<LazyList.size(values);i++)
                                    {
                                        overridden_query_string.append("&").append(name).append("=").append(LazyList.get(values, i));
                                    }
                                }
                            }
                            
                            query = query + overridden_query_string;
                        }
                        else 
                        {
                            query=query+"&"+old_query;
                        }
                   }

                    baseRequest.setParameters(parameters);
                    baseRequest.setQueryString(query);
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
                baseRequest.setAttributes(attr);
                baseRequest.setQueryString(query);
                
                _contextHandler.handle(_path,baseRequest, (HttpServletRequest)request, (HttpServletResponse)response);
                
                if (baseRequest.getConnection().getResponse().isWriting())
                {
                    try {response.getWriter().close();}
                    catch(IllegalStateException e) 
                    { 
                        response.getOutputStream().close(); 
                    }
                }
                else
                {
                    try {response.getOutputStream().close();}
                    catch(IllegalStateException e) 
                    { 
                        response.getWriter().close(); 
                    }
                }
            }
        }
        finally
        {
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
