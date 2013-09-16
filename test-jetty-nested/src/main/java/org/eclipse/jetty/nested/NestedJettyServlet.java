//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.nested;

import java.io.File;
import java.io.IOException;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.bio.SocketConnector;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlConfiguration;

/**
 * Nested Jetty Servlet.
 * <p>
 * This servlet runs Jetty as a nested server inside another servlet container.   The requests received by
 * this servlet are routed via a {@link NestedConnector} to the nested jetty servlet and handled by jetty contexts,
 * handlers, webapps and/or servlets.
 * <p>
 * The servlet can be configured with the following init parameters:<ul>
 * <li>debug - if true then jetty debugging is turned on</li>
 * <li>webapp - set to the resource path of the webapplication to deploy
 * <li>jetty.xml - set the the resource path of a jetty xml file used to configure the server
 * </ul>
 *
 */
public class NestedJettyServlet implements Servlet
{
    private Server _server;
    private ServletConfig _config;
    private ServletContext _context;
    private NestedConnector _connector;
    
    public void init(ServletConfig config) throws ServletException
    {    
        ClassLoader orig = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(NestedJettyServlet.class.getClassLoader());
            _config=config;
            _context=config.getServletContext();
            
            Log.getLog().setDebugEnabled(Boolean.parseBoolean(_config.getInitParameter("debug")));
            
            String jetty_xml=config.getInitParameter("jetty.xml");
            if (jetty_xml!=null)
            {
                XmlConfiguration xml_config = new XmlConfiguration(_context.getResourceAsStream(jetty_xml));
                _server=(Server)xml_config.configure();
            }
            if (_server==null)
                _server=new Server();
            
            if (_server.getConnectors().length==0)
            {
                _connector=new NestedConnector();
                _server.addConnector(_connector);
            }
            else
                _connector=(NestedConnector)_server.getConnectors()[0];
            
            WebAppContext webapp = new WebAppContext();
            
            webapp.setContextPath(_context.getContextPath());
            webapp.setTempDirectory(new File((File)_context.getAttribute("javax.servlet.context.tempdir"),"jetty"));
            String docroot=config.getInitParameter("webapp");
           
            String realpath=_context.getRealPath(docroot);
            if (realpath!=null)
                webapp.setWar(realpath);
            else
                webapp.setWar(_context.getResource(docroot).toString());

            _server.setHandler(webapp);

            _server.start();
            _context.log("Started Jetty/"+_server.getVersion()+" for "+webapp.getWar()+" nested in "+_context.getServerInfo());
        }
        catch(Exception e)
        {
            throw new ServletException(e);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(orig);
        }
    }

    public ServletConfig getServletConfig()
    {
        return _config;
    }

    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
    {
        _connector.service(req,res);
    }

    public String getServletInfo()
    {
        return this.toString();
    }

    public void destroy()
    {
        try
        {
            _server.stop();
        }
        catch(Exception e)
        {
            _context.log("stopping",e);
        }
    }
}
