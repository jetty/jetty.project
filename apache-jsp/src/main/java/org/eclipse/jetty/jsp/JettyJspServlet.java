//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.jsp;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.jasper.servlet.JspServlet;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;

/**
 * JettyJspServlet
 *
 * Wrapper for the jsp servlet that handles receiving requests mapped from 
 * jsp-property-groups. Mappings could be wildcard urls like "/*", which would
 * include welcome files, but we need those to be handled by the DefaultServlet.
 */
public class JettyJspServlet extends JspServlet
{

    /**
     * 
     */
    private static final long serialVersionUID = -5387857473125086791L;

    
    
    
    
    @Override
    public void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        HttpServletRequest request = null;
        if (req instanceof HttpServletRequest)
            request = (HttpServletRequest)req;
        else
            throw new ServletException("Request not HttpServletRequest");

        String servletPath=null;
        String pathInfo=null;
        if (request.getAttribute("javax.servlet.include.request_uri")!=null)
        {
            servletPath=(String)request.getAttribute("javax.servlet.include.servlet_path");
            pathInfo=(String)request.getAttribute("javax.servlet.include.path_info");
            if (servletPath==null)
            {
                servletPath=request.getServletPath();
                pathInfo=request.getPathInfo();
            }
        }
        else
        {
            servletPath = request.getServletPath();
            pathInfo = request.getPathInfo();
        }
        
        String pathInContext = URIUtil.addPaths(servletPath,pathInfo);
    
        String jspFile = getInitParameter("jspFile");

        //if this is a forced-path from a jsp-file, we want the jsp servlet to handle it,
        //otherwise the default servlet might handle it
        if (jspFile == null)
        {
            if (pathInContext.endsWith("/"))
            {
                //dispatch via forward to the default servlet
                getServletContext().getNamedDispatcher("default").forward(req, resp);
                return;
            }
            else
            {      
                //check if it resolves to a directory
                Resource resource = ((ContextHandler.Context)getServletContext()).getContextHandler().getResource(pathInContext);    

                if (resource!=null && resource.isDirectory())
                {
                    //dispatch via forward to the default servlet
                    getServletContext().getNamedDispatcher("default").forward(req, resp);
                    return;
                }
            }
        }
        
        //fall through to the normal jsp servlet handling
        super.service(req, resp);
    }

    
}
