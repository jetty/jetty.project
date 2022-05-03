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

package org.eclipse.jetty.ee10.jaas.spi;

import java.io.File;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.AppConfigurationEntry.LoginModuleControlFlag;
import javax.security.auth.login.Configuration;

import org.eclipse.jetty.ee10.jaas.JAASLoginService;
import org.eclipse.jetty.ee10.jaas.PropertyUserStoreManager;
import org.eclipse.jetty.ee10.jaas.TestServlet;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee10.servlet.security.DefaultIdentityService;
import org.eclipse.jetty.ee10.servlet.security.PropertyUserStore;
import org.eclipse.jetty.ee10.servlet.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

public class PropertyFileLoginModuleTest
{
    private Server _server;
    private LocalConnector _connector;
    
    @BeforeEach
    public void setUp() throws Exception
    {
        _server = new Server();

        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

    }
    
    @AfterEach
    public void tearDown() throws Exception
    {
        _server.stop();
    }
    
    @Test
    public void testPropertyFileLoginModule() throws Exception
    {
        //configure for PropertyFileLoginModule
        File loginProperties = MavenTestingUtils.getTestResourceFile("login.properties");

        Configuration testConfig = new Configuration()
        {
            @Override
            public AppConfigurationEntry[] getAppConfigurationEntry(String name)
            { 
                return new AppConfigurationEntry[]{new AppConfigurationEntry(PropertyFileLoginModule.class.getName(), 
                                                                             LoginModuleControlFlag.REQUIRED,
                                                                             Collections.singletonMap("file", loginProperties.getAbsolutePath()))};
            }
        };

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/ctx");
        context.addServlet(new TestServlet(Arrays.asList("role1", "role2", "role3"), Arrays.asList("role4")), "/");
        _server.setHandler(context);
        
        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        security.setAuthenticator(new BasicAuthenticator());
        context.setSecurityHandler(security);
        
        JAASLoginService ls = new JAASLoginService("foo");
        ls.setCallbackHandlerClass("org.eclipse.jetty.ee10.jaas.callback.DefaultCallbackHandler");
        ls.setIdentityService(new DefaultIdentityService());
        ls.setConfiguration(testConfig);
        _server.addBean(ls, true);

        _server.start();
        
        //test that the manager is created when the JAASLoginService starts
        PropertyUserStoreManager mgr = ls.getBean(PropertyUserStoreManager.class);
        assertThat(mgr, notNullValue());

        //test the PropertyFileLoginModule authentication and authorization
        String response = _connector.getResponse("GET /ctx/test HTTP/1.0\n" + "Authorization: Basic " +
            Base64.getEncoder().encodeToString("fred:pwd".getBytes(ISO_8859_1)) + "\n\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));

        //Test that the PropertyUserStore is created by the PropertyFileLoginModule
        PropertyUserStore store = mgr.getPropertyUserStore(loginProperties.getAbsolutePath());
        assertThat(store, is(notNullValue()));
        assertThat(store.isRunning(), is(true));
        assertThat(store.isHotReload(), is(false));

        //test that the PropertyUserStoreManager is stopped and all PropertyUserStores stopped
        _server.stop();
        assertThat(mgr.isStopped(), is(true));
        assertThat(mgr.getPropertyUserStore(loginProperties.getAbsolutePath()), is(nullValue()));
        assertThat(store.isStopped(), is(true));
    }
}
