//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.security.Constraint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;

/**
 * DatabaseLoginServiceTestServer
 */
public class DatabaseLoginServiceTestServer
{
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseLoginServiceTestServer.class);
    private static final Logger MARIADB_LOG = LoggerFactory.getLogger("org.eclipse.jetty.security.MariaDbLogs");
    
    static MariaDBContainer MARIA_DB;

    protected static final String MARIA_DB_USER = "beer";
    protected static final String MARIA_DB_PASSWORD = "pacific_ale";
    public static String MARIA_DB_DRIVER_CLASS;
    public static String MARIA_DB_URL;
    public static String MARIA_DB_FULL_URL;
    
    protected Server _server;
    protected static String _protocol;
    protected static URI _baseUri;
    protected LoginService _loginService;
    protected String _resourceBase;
    protected TestHandler _handler;
    protected static String _requestContent;

    protected static File _dbRoot;

    static
    {
        try
        {
            MARIA_DB =
                new MariaDBContainer("mariadb:" + System.getProperty("mariadb.docker.version", "10.3.6"))
                .withUsername(MARIA_DB_USER)
                .withPassword(MARIA_DB_PASSWORD);
            MARIA_DB = (MariaDBContainer)MARIA_DB.withInitScript("createdb.sql");
            MARIA_DB = (MariaDBContainer)MARIA_DB.withLogConsumer(new Slf4jLogConsumer(MARIADB_LOG));
            MARIA_DB.start();
            String containerIpAddress =  MARIA_DB.getContainerIpAddress();
            int mariadbPort = MARIA_DB.getMappedPort(3306);
            MARIA_DB_URL = MARIA_DB.getJdbcUrl();
            MARIA_DB_FULL_URL = MARIA_DB_URL + "?user=" + MARIA_DB_USER + "&password=" + MARIA_DB_PASSWORD;
            MARIA_DB_DRIVER_CLASS = MARIA_DB.getDriverClassName();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e.getMessage(), e);
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

        @Override
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

    public DatabaseLoginServiceTestServer()
    {
        _server = new Server(0);
    }

    public void setLoginService(LoginService loginService)
    {
        _loginService = loginService;
    }

    public void setResourceBase(String resourceBase)
    {
        _resourceBase = resourceBase;
    }

    public void start() throws Exception
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
        constraint.setAuthenticate(true);
        constraint.setRoles(new String[]{"user", "admin"});

        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/*");
        mapping.setConstraint(constraint);

        Set<String> knownRoles = new HashSet<>();
        knownRoles.add("user");
        knownRoles.add("admin");

        security.setConstraintMappings(Collections.singletonList(mapping), knownRoles);
        security.setAuthenticator(new BasicAuthenticator());
        security.setLoginService(_loginService);

        ServletContextHandler root = new ServletContextHandler();
        root.setContextPath("/");
        root.setResourceBase(_resourceBase);
        ServletHolder servletHolder = new ServletHolder(new DefaultServlet());
        servletHolder.setInitParameter("gzip", "true");
        root.addServlet(servletHolder, "/*");

        _handler = new TestHandler(_resourceBase);

        security.setHandler(new HandlerList(_handler, root));
    }
}
