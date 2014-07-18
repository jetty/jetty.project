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


package org.eclipse.jetty;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.derby.tools.ij;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.security.Constraint;


/**
 * DatabaseLoginServiceTestServer
 */
public class DatabaseLoginServiceTestServer
{
    private static final Logger LOG = Log.getLogger(DatabaseLoginServiceTestServer.class);
    protected Server _server;
    protected static String _protocol;
    protected static URI _baseUri;
    protected LoginService _loginService;
    protected String _resourceBase;
    protected TestHandler _handler;
    protected static String _requestContent;
    
    protected static boolean createDB(String homeDir, File scriptFile, String dbUrl)
    {
        try (FileInputStream fileStream = new FileInputStream(scriptFile))
        {
            Loader.loadClass(fileStream.getClass(), "org.apache.derby.jdbc.EmbeddedDriver").newInstance();
            Connection connection = DriverManager.getConnection(dbUrl, "", "");

            OutputStream out = new ByteArrayOutputStream();
            int result = ij.runScript(connection, fileStream, "UTF-8", out, "UTF-8");

            return (result==0);
        }
        catch (Exception e)
        {
            LOG.warn("Unable to create EmbeddedDriver",e);
            return false;
        }
    }
  
    public static class TestHandler extends AbstractHandler 
    {
        private final String _resourcePath;
        private String _requestContent;
        

        public TestHandler(String repositoryPath) 
        {
            _resourcePath = repositoryPath;
        }

        public void handle(String target, org.eclipse.jetty.server.Request baseRequest,
                HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException
        {
            if (baseRequest.isHandled())
            {
                return;
            }

            OutputStream out = null;

            if (baseRequest.getMethod().equals("PUT"))
            {
                baseRequest.setHandled(true);

                File file = new File(_resourcePath, URLDecoder.decode(request.getPathInfo()));
                FS.ensureDirExists(file.getParentFile());

                out = new FileOutputStream(file);

                response.setStatus(HttpServletResponse.SC_CREATED);
            }

            if (baseRequest.getMethod().equals("POST"))
            {
                baseRequest.setHandled(true);
                out = new ByteArrayOutputStream();

                response.setStatus(HttpServletResponse.SC_OK);
            }

            if (out != null)
            {
                try (ServletInputStream in = request.getInputStream())
                {
                    IO.copy(in, out);
                }
                finally
                {
                    out.close();
                }

                if (!(out instanceof FileOutputStream))
                    _requestContent = out.toString();
            }
        }
        
        public String getRequestContent()
        {
            return _requestContent;
        }
    }
    
    
    public DatabaseLoginServiceTestServer ()
    {
        _server = new Server(0);
    }
    
    public void setLoginService (LoginService loginService)
    {
        _loginService = loginService;
    }
    
    public void setResourceBase (String resourceBase)
    {
        _resourceBase = resourceBase;
    }
 
    
    public void start () throws Exception
    {
        configureServer();
        _server.start();
        //_server.dumpStdErr();
        _baseUri = _server.getURI();
    }
    
    public void stop() throws Exception
    {
        _server.stop();
    }
    
    public URI getBaseUri()
    {
        return _baseUri;
    }
   
    public TestHandler getTestHandler()
    {
        return _handler;
    }
 
    public Server getServer()
    {
        return _server;
    }
    
    protected void configureServer() throws Exception
    {
        _protocol = "http";
        _server.addBean(_loginService);

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        _server.setHandler(security);

        Constraint constraint = new Constraint();
        constraint.setName("auth");
        constraint.setAuthenticate( true );
        constraint.setRoles(new String[]{"user", "admin"});

        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec( "/*" );
        mapping.setConstraint( constraint );

        Set<String> knownRoles = new HashSet<>();
        knownRoles.add("user");
        knownRoles.add("admin");

        security.setConstraintMappings(Collections.singletonList(mapping), knownRoles);
        security.setAuthenticator(new BasicAuthenticator());
        security.setLoginService(_loginService);

        ServletContextHandler root = new ServletContextHandler();
        root.setContextPath("/");
        root.setResourceBase(_resourceBase);
        ServletHolder servletHolder = new ServletHolder( new DefaultServlet() );
        servletHolder.setInitParameter( "gzip", "true" );
        root.addServlet( servletHolder, "/*" );

        _handler = new TestHandler(_resourceBase);

        HandlerCollection handlers = new HandlerCollection();
        handlers.setHandlers(new Handler[]{_handler, root});
        security.setHandler(handlers);
    }

}
