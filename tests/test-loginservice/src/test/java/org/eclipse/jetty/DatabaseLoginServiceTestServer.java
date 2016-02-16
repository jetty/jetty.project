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
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.security.Constraint;


/**
 * DatabaseLoginServiceTestServer
 */
public class DatabaseLoginServiceTestServer
{
    protected static String __dbURL = "jdbc:derby:loginservice;create=true";
    protected Server _server;
    protected static String _protocol;
    protected static URI _baseUri;
    protected LoginService _loginService;
    protected String _resourceBase;
    protected TestHandler _handler;
    private static File commonDerbySystemHome;
    protected static String _requestContent;
    
    protected static File _dbRoot;


    static
    {
        _dbRoot = new File(MavenTestingUtils.getTargetTestingDir("loginservice-test"), "derby");
        FS.ensureDirExists(_dbRoot);
        System.setProperty("derby.system.home", _dbRoot.getAbsolutePath());
    }

    public static File getDbRoot ()
    {
        return _dbRoot;
    }

    public static int runscript (File scriptFile) throws Exception
    {  
        //System.err.println("Running script:"+scriptFile.getAbsolutePath());
        try (FileInputStream fileStream = new FileInputStream(scriptFile))
        {
            Loader.loadClass("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
            Connection connection = DriverManager.getConnection(__dbURL, "", "");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            return ij.runScript(connection, fileStream, "UTF-8", out, "UTF-8");
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

                File file = new File(_resourcePath, URLDecoder.decode(request.getPathInfo(), "utf-8"));
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
