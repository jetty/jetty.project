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

package org.eclipse.jetty.client;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jetty.client.security.Realm;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class SslSecuredContentExchangeTest
extends ContentExchangeTest
{ 
    protected void configureServer(Server server)
        throws Exception
    {
        setProtocol("https");
        setRealm(new Realm()
                 {
                     public String getId()
                     {
                         return "MyRealm";
                     }
                
                     public String getPrincipal()
                     {
                         return "jetty";
                     }
                
                     public String getCredentials()
                     {
                         return "jetty";
                     }
                 });
                        
        SslSelectChannelConnector connector = new SslSelectChannelConnector();
        File keystore = MavenTestingUtils.getTestResourceFile("keystore");
        SslContextFactory cf = connector.getSslContextFactory();
        cf.setKeyStorePath(keystore.getAbsolutePath());
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
        server.addConnector(connector);

        File realmPropFile = MavenTestingUtils.getTestResourceFile("realm.properties");
        LoginService loginService = new HashLoginService("MyRealm",realmPropFile.getAbsolutePath());
        server.addBean(loginService); 

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        server.setHandler(security);

        Constraint constraint = new Constraint();
        constraint.setName("auth");
        constraint.setAuthenticate( true );
        constraint.setRoles(new String[]{"user", "admin"});

        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec( "/*" );
        mapping.setConstraint( constraint );

        Set<String> knownRoles = new HashSet<String>();
        knownRoles.add("user");
        knownRoles.add("admin");
        
        security.setConstraintMappings(Collections.singletonList(mapping), knownRoles);
        security.setAuthenticator(new BasicAuthenticator());
        security.setLoginService(loginService);
        security.setStrict(false);
        
        ServletContextHandler root = new ServletContextHandler();
        root.setContextPath("/");
        root.setResourceBase(getBasePath());
        ServletHolder servletHolder = new ServletHolder( new DefaultServlet() );
        servletHolder.setInitParameter( "gzip", "true" );
        root.addServlet( servletHolder, "/*" );    

        Handler handler = new TestHandler(getBasePath());       
        
        HandlerCollection handlers = new HandlerCollection();
        handlers.setHandlers(new Handler[]{handler, root});
        security.setHandler(handlers);
    }
}
