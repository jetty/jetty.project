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

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.AbstractHttpConnection;
import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/**  Dynamic Servlet Invoker.  
 * This servlet invokes anonymous servlets that have not been defined   
 * in the web.xml or by other means. The first element of the pathInfo  
 * of a request passed to the envoker is treated as a servlet name for  
 * an existing servlet, or as a class name of a new servlet.            
 * This servlet is normally mapped to /servlet/*                        
 * This servlet support the following initParams:                       
 * <PRE>                                                                     
 *  nonContextServlets       If false, the invoker can only load        
 *                           servlets from the contexts classloader.    
 *                           This is false by default and setting this  
 *                           to true may have security implications.    
 *                                                                      
 *  verbose                  If true, log dynamic loads                 
 *                                                                      
 *  *                        All other parameters are copied to the     
 *                           each dynamic servlet as init parameters    
 * </PRE>
 * @version $Id: Invoker.java 4780 2009-03-17 15:36:08Z jesse $
 * 
 */
public class Invoker extends HttpServlet
{
    private static final Logger LOG = Log.getLogger(Invoker.class);


    private ContextHandler _contextHandler;
    private ServletHandler _servletHandler;
    private Map.Entry _invokerEntry;
    private Map _parameters;
    private boolean _nonContextServlets;
    private boolean _verbose;
        
    /* ------------------------------------------------------------ */
    public void init()
    {
        ServletContext config=getServletContext();
        _contextHandler=((ContextHandler.Context)config).getContextHandler();

        Handler handler=_contextHandler.getHandler();
        while (handler!=null && !(handler instanceof ServletHandler) && (handler instanceof HandlerWrapper))
            handler=((HandlerWrapper)handler).getHandler();
        _servletHandler = (ServletHandler)handler;
        Enumeration e = getInitParameterNames();
        while(e.hasMoreElements())
        {
            String param=(String)e.nextElement();
            String value=getInitParameter(param);
            String lvalue=value.toLowerCase(Locale.ENGLISH);
            if ("nonContextServlets".equals(param))
            {
                _nonContextServlets=value.length()>0 && lvalue.startsWith("t");
            }
            if ("verbose".equals(param))
            {
                _verbose=value.length()>0 && lvalue.startsWith("t");
            }
            else
            {
                if (_parameters==null)
                    _parameters=new HashMap();
                _parameters.put(param,value);
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    protected void service(HttpServletRequest request, HttpServletResponse response)
	throws ServletException, IOException
    {
        // Get the requested path and info
        boolean included=false;
        String servlet_path=(String)request.getAttribute(Dispatcher.INCLUDE_SERVLET_PATH);
        if (servlet_path==null)
            servlet_path=request.getServletPath();
        else
            included=true;
        String path_info = (String)request.getAttribute(Dispatcher.INCLUDE_PATH_INFO);
        if (path_info==null)
            path_info=request.getPathInfo();
        
        // Get the servlet class
        String servlet = path_info;
        if (servlet==null || servlet.length()<=1 )
        {
            response.sendError(404);
            return;
        }
        
        
        int i0=servlet.charAt(0)=='/'?1:0;
        int i1=servlet.indexOf('/',i0);
        servlet=i1<0?servlet.substring(i0):servlet.substring(i0,i1);

        // look for a named holder
        ServletHolder[] holders = _servletHandler.getServlets();
        ServletHolder holder = getHolder (holders, servlet);
       
        if (holder!=null)
        {
            // Found a named servlet (from a user's web.xml file) so
            // now we add a mapping for it
            if (LOG.isDebugEnabled())
                LOG.debug("Adding servlet mapping for named servlet:"+servlet+":"+URIUtil.addPaths(servlet_path,servlet)+"/*");
            ServletMapping mapping = new ServletMapping();
            mapping.setServletName(servlet);
            mapping.setPathSpec(URIUtil.addPaths(servlet_path,servlet)+"/*");
            _servletHandler.setServletMappings((ServletMapping[])LazyList.addToArray(_servletHandler.getServletMappings(), mapping, ServletMapping.class));
        }
        else
        {
            // look for a class mapping
            if (servlet.endsWith(".class"))
                servlet=servlet.substring(0,servlet.length()-6);
            if (servlet==null || servlet.length()==0)
            {
                response.sendError(404);
                return;
            }   
        
            synchronized(_servletHandler)
            {
                // find the entry for the invoker (me)
                 _invokerEntry=_servletHandler.getHolderEntry(servlet_path);
            
                // Check for existing mapping (avoid threaded race).
                String path=URIUtil.addPaths(servlet_path,servlet);
                Map.Entry entry = _servletHandler.getHolderEntry(path);
               
                if (entry!=null && !entry.equals(_invokerEntry))
                {
                    // Use the holder
                    holder=(ServletHolder)entry.getValue();
                }
                else
                {
                    // Make a holder
                    if (LOG.isDebugEnabled())
                        LOG.debug("Making new servlet="+servlet+" with path="+path+"/*");
                    holder=_servletHandler.addServletWithMapping(servlet, path+"/*");
                    
                    if (_parameters!=null)
                        holder.setInitParameters(_parameters);
                    
                    try {holder.start();}
                    catch (Exception e)
                    {
                        LOG.debug(e);
                        throw new UnavailableException(e.toString());
                    }
                    
                    // Check it is from an allowable classloader
                    if (!_nonContextServlets)
                    {
                        Object s=holder.getServlet();
                        
                        if (_contextHandler.getClassLoader()!=
                            s.getClass().getClassLoader())
                        {
                            try 
                            {
                                holder.stop();
                            } 
                            catch (Exception e) 
                            {
                                LOG.ignore(e);
                            }
                            
                            LOG.warn("Dynamic servlet "+s+
                                         " not loaded from context "+
                                         request.getContextPath());
                            throw new UnavailableException("Not in context");
                        }
                    }

                    if (_verbose && LOG.isDebugEnabled())
                        LOG.debug("Dynamic load '"+servlet+"' at "+path);
                }
            }
        }
        
        if (holder!=null)
        {
            final Request baseRequest=(request instanceof Request)?((Request)request):AbstractHttpConnection.getCurrentConnection().getRequest();
            holder.handle(baseRequest,
                    new InvokedRequest(request,included,servlet,servlet_path,path_info),
                          response);
        }
        else
        {
            LOG.info("Can't find holder for servlet: "+servlet);
            response.sendError(404);
        }
            
        
    }

    /* ------------------------------------------------------------ */
    class InvokedRequest extends HttpServletRequestWrapper
    {
        String _servletPath;
        String _pathInfo;
        boolean _included;
        
        /* ------------------------------------------------------------ */
        InvokedRequest(HttpServletRequest request,
                boolean included,
                String name,
                String servletPath,
                String pathInfo)
        {
            super(request);
            _included=included;
            _servletPath=URIUtil.addPaths(servletPath,name);
            _pathInfo=pathInfo.substring(name.length()+1);
            if (_pathInfo.length()==0)
                _pathInfo=null;
        }
        
        /* ------------------------------------------------------------ */
        public String getServletPath()
        {
            if (_included)
                return super.getServletPath();
            return _servletPath;
        }
        
        /* ------------------------------------------------------------ */
        public String getPathInfo()
        {
            if (_included)
                return super.getPathInfo();
            return _pathInfo;
        }
        
        /* ------------------------------------------------------------ */
        public Object getAttribute(String name)
        {
            if (_included)
            {
                if (name.equals(Dispatcher.INCLUDE_REQUEST_URI))
                    return URIUtil.addPaths(URIUtil.addPaths(getContextPath(),_servletPath),_pathInfo);
                if (name.equals(Dispatcher.INCLUDE_PATH_INFO))
                    return _pathInfo;
                if (name.equals(Dispatcher.INCLUDE_SERVLET_PATH))
                    return _servletPath;
            }
            return super.getAttribute(name);
        }
    }
    
    
    private ServletHolder getHolder(ServletHolder[] holders, String servlet)
    {
        if (holders == null)
            return null;
       
        ServletHolder holder = null;
        for (int i=0; holder==null && i<holders.length; i++)
        {
            if (holders[i].getName().equals(servlet))
            {
                holder = holders[i];
            }
        }
        return holder;
    }
}
