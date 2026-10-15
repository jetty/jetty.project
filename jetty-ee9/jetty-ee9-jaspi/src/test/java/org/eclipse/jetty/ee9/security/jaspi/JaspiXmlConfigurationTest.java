//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.security.jaspi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.AuthConfigProvider;
import org.eclipse.jetty.ee9.nested.ContextHandler;
import org.eclipse.jetty.ee9.nested.ServletConstraint;
import org.eclipse.jetty.ee9.security.ConstraintMapping;
import org.eclipse.jetty.ee9.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee9.security.jaspi.provider.JaspiAuthConfigProvider;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.security.Password;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code etc/jaspi} XML files shipped by this module, loaded the way the {@code jaspi} and
 * {@code jaspi-demo} start modules load them.
 */
@Isolated("Sets the global AuthConfigFactory")
public class JaspiXmlConfigurationTest
{
    private static final String CONTEXT_PATH = "/test";
    private static final String APP_CONTEXT = "server " + CONTEXT_PATH;

    private Server _server;
    private LocalConnector _connector;
    private Map<String, Object> _idMap;

    @BeforeEach
    public void before()
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);
        _idMap = new HashMap<>();
        _idMap.put("Server", _server);
    }

    @AfterEach
    public void after()
    {
        LifeCycle.stop(_server);
        AuthConfigFactory.setFactory(null);
    }

    /**
     * @param suffix the part of the file name after the module name, which the EE8 sources generated
     * from this test must resolve against their own module.
     */
    private void configure(String suffix) throws Exception
    {
        Path projectBase = MavenPaths.projectBase();
        Path xml = projectBase.resolve("src/main/config/etc/jaspi/" + projectBase.getFileName() + suffix);
        assertTrue(Files.isRegularFile(xml));
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            XmlConfiguration configuration = new XmlConfiguration(resourceFactory.newResource(xml), _idMap, new HashMap<>());
            configuration.configure();
        }
    }

    @Test
    public void testDefaultXmlSetsTheFactoryAsAnUnmanagedBean() throws Exception
    {
        configure("-default.xml");

        AuthConfigFactory factory = AuthConfigFactory.getFactory();
        assertThat(factory, instanceOf(DefaultAuthConfigFactory.class));
        assertThat(_server.getBean(DefaultAuthConfigFactory.class), sameInstance(factory));
        assertThat(_server.isManaged(factory), is(false));
    }

    @Test
    public void testDemoXmlRegistersBasicAuthenticationForTheTestContext() throws Exception
    {
        configure("-default.xml");
        configure("-demo.xml");

        AuthConfigFactory factory = AuthConfigFactory.getFactory();
        AuthConfigProvider provider = factory.getConfigProvider(JaspiAuthenticatorFactory.MESSAGE_LAYER, APP_CONTEXT, null);
        assertThat(provider, instanceOf(JaspiAuthConfigProvider.class));

        String[] registrationIds = factory.getRegistrationIDs(provider);
        assertThat(registrationIds.length, is(1));
        AuthConfigFactory.RegistrationContext registration = factory.getRegistrationContext(registrationIds[0]);
        assertNotNull(registration);
        assertThat(registration.getMessageLayer(), is(JaspiAuthenticatorFactory.MESSAGE_LAYER));
        assertThat(registration.getAppContext(), is(APP_CONTEXT));
        assertThat(registration.getDescription(), is("A simple provider using HTTP BASIC authentication."));
    }

    @Test
    public void testDemoXmlAuthenticatesTheTestContextWithBasicAuthentication() throws Exception
    {
        configure("-default.xml");
        configure("-demo.xml");
        startTestContext();

        String response = _connector.getResponse("GET " + CONTEXT_PATH + "/jaspi/test HTTP/1.0\n\n");
        assertThat(response, startsWith("HTTP/1.1 401 Unauthorized"));
        assertThat(response, containsString("WWW-Authenticate: Basic realm=\"Test Realm\""));

        response = _connector.getResponse("GET " + CONTEXT_PATH + "/jaspi/test HTTP/1.0\nAuthorization: Basic " +
            Base64.getEncoder().encodeToString("user:password".getBytes(ISO_8859_1)) + "\n\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    private void startTestContext() throws Exception
    {
        JaspiTest.TestLoginService loginService = new JaspiTest.TestLoginService("Test Realm");
        loginService.putUser("user", new Password("password"), new String[] {"users"});
        _server.addBean(loginService);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        _server.setHandler(contexts);

        ContextHandler context = new ContextHandler();
        contexts.addHandler(context);
        context.setContextPath(CONTEXT_PATH);

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        context.setHandler(security);
        security.setAuthenticatorFactory(new JaspiAuthenticatorFactory());
        security.setHandler(new JaspiTest.TestHandler());

        ServletConstraint constraint = new ServletConstraint("All", "users");
        constraint.setAuthenticate(true);
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/jaspi/*");
        mapping.setConstraint(constraint);
        security.addConstraintMapping(mapping);

        _server.start();
    }
}
