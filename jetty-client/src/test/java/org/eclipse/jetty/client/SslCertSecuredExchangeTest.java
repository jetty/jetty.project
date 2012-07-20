// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.security.Principal;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.security.auth.Subject;

import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.MappedLoginService.KnownUser;
import org.eclipse.jetty.security.authentication.ClientCertAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class SslCertSecuredExchangeTest// extends ContentExchangeTest
{ 
    // certificate is valid until Jan 1, 2050
    private String _keypath = MavenTestingUtils.getTargetFile("test-policy/validation/jetty-valid.keystore").getAbsolutePath();
    private String _trustpath = MavenTestingUtils.getTargetFile("test-policy/validation/jetty-trust.keystore").getAbsolutePath();
    private String _clientpath = MavenTestingUtils.getTargetFile("test-policy/validation/jetty-client.keystore").getAbsolutePath();
    private String _crlpath = MavenTestingUtils.getTargetFile("test-policy/validation/crlfile.pem").getAbsolutePath();
    private String _password = "OBF:1wnl1sw01ta01z0f1tae1svy1wml";

    protected void configureServer(Server server)
        throws Exception
    {
        //setProtocol("https");
                        
        SslSelectChannelConnector connector = new SslSelectChannelConnector();
        SslContextFactory cf = connector.getSslContextFactory();
        cf.setValidateCerts(true);
        cf.setCrlPath(_crlpath);
        cf.setNeedClientAuth(true);
        cf.setKeyStorePath(_keypath);
        cf.setKeyStorePassword(_password);
        cf.setKeyManagerPassword(_password);
        cf.setTrustStore(_trustpath);
        cf.setTrustStorePassword(_password);
        server.addConnector(connector);

        LoginService loginService = new LoginService() {
            public String getName()
            {
                return "MyLoginService";
            }

            public UserIdentity login(String username, Object credentials)
            {
                return new UserIdentity() {
                    public Subject getSubject()
                    {
                        Subject subject = new Subject();
                        subject.getPrincipals().add(getUserPrincipal());
                        subject.setReadOnly();
                        return subject;
                    }

                    public Principal getUserPrincipal()
                    {
                        return new KnownUser("client", new Credential() {
                            @Override
                            public boolean check(Object credentials)
                            {
                                return true;
                            }
                        });
                    }

                    public boolean isUserInRole(String role, Scope scope) { return true; }
                };
            }

            public boolean validate(UserIdentity user) { return true; }

            public IdentityService getIdentityService() { return null; }

            public void setIdentityService(IdentityService service) {}

            public void logout(UserIdentity user) {}
            
        };
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
        security.setLoginService(loginService);

        ClientCertAuthenticator auth = new ClientCertAuthenticator();
        auth.setValidateCerts(true);
        auth.setCrlPath(_crlpath);
        auth.setTrustStore(_trustpath);
        auth.setTrustStorePassword(_password);
        security.setAuthenticator(auth);
        security.setAuthMethod(auth.getAuthMethod());
        security.setRealmName("MyRealm");
        security.setStrict(true);
        
        ServletContextHandler root = new ServletContextHandler();
        root.setContextPath("/");
       // root.setResourceBase(getBasePath());
        ServletHolder servletHolder = new ServletHolder( new DefaultServlet() );
        servletHolder.setInitParameter( "gzip", "true" );
        root.addServlet( servletHolder, "/*" );    

      //  Handler handler = new TestHandler(getBasePath());
        
        HandlerCollection handlers = new HandlerCollection();
      //  handlers.setHandlers(new Handler[]{handler, root});
        security.setHandler(handlers);
    }
    
//    @Override
//    protected void configureClient(HttpClient client) throws Exception
//    {
//        SslContextFactory cf = client.getSslContextFactory();
//        cf.setValidateCerts(true);
//        cf.setCrlPath(_crlpath);
//        
//        cf.setCertAlias("client");
//        cf.setKeyStorePath(_clientpath);
//        cf.setKeyStorePassword(_password);
//        cf.setKeyManagerPassword(_password);
//        
//        cf.setTrustStore(_trustpath);
//        cf.setTrustStorePassword(_password);
//    }
}
