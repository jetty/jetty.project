//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.client.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.kerby.kerberos.kerb.server.SimpleKdcServer;
import org.eclipse.jetty.client.AbstractHttpClientServerTest;
import org.eclipse.jetty.client.EmptyServerHandler;
import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.security.ConfigurableSpnegoLoginService;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.authentication.AuthorizationService;
import org.eclipse.jetty.security.authentication.ConfigurableSpnegoAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.session.DefaultSessionIdManager;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.security.Constraint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

// Apparently only JDK 11 is able to run these tests.
// See for example: https://bugs.openjdk.java.net/browse/JDK-8202439
// where apparently the compiler gets the AES CPU instructions wrong.
@DisabledOnJre({JRE.JAVA_8, JRE.JAVA_9, JRE.JAVA_10})
public class SPNEGOAuthenticationTest extends AbstractHttpClientServerTest
{
    private static final Logger LOG = Log.getLogger(SPNEGOAuthenticationTest.class);

    static
    {
        if (LOG.isDebugEnabled())
        {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
            System.setProperty("sun.security.jgss.debug", "true");
            System.setProperty("sun.security.krb5.debug", "true");
            System.setProperty("sun.security.spnego.debug", "true");
        }
    }

    private Path testDirPath = MavenTestingUtils.getTargetTestingPath(SPNEGOAuthenticationTest.class.getSimpleName());
    private String clientName = "spnego_client";
    private String clientPassword = "spnego_client_pwd";
    private String serviceName = "srvc";
    private String serviceHost = "localhost";
    private String realm = "jetty.org";
    private Path realmPropsPath = MavenTestingUtils.getTestResourcePath("realm.properties");
    private Path serviceKeyTabPath = testDirPath.resolve("service.keytab");
    private Path clientKeyTabPath = testDirPath.resolve("client.keytab");
    private SimpleKdcServer kdc;
    private ConfigurableSpnegoAuthenticator authenticator;

    @BeforeEach
    public void prepare() throws Exception
    {
        IO.delete(testDirPath.toFile());
        Files.createDirectories(testDirPath);
        System.setProperty("java.security.krb5.conf", testDirPath.toAbsolutePath().toString());

        kdc = new SimpleKdcServer();
        kdc.setAllowUdp(false);
        kdc.setAllowTcp(true);
        kdc.setKdcRealm(realm);
        kdc.setWorkDir(testDirPath.toFile());
        kdc.init();

        kdc.createAndExportPrincipals(serviceKeyTabPath.toFile(), serviceName + "/" + serviceHost);
        kdc.createPrincipal(clientName + "@" + realm, clientPassword);
        kdc.exportPrincipal(clientName, clientKeyTabPath.toFile());
        kdc.start();

        if (LOG.isDebugEnabled())
        {
            LOG.debug("KDC started on port {}", kdc.getKdcTcpPort());
            String krb5 = Files.readAllLines(testDirPath.resolve("krb5.conf")).stream()
                .filter(line -> !line.startsWith("#"))
                .collect(Collectors.joining(System.lineSeparator()));
            LOG.debug("krb5.conf{}{}", System.lineSeparator(), krb5);
        }
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (kdc != null)
            kdc.stop();
    }

    private void startSPNEGO(Scenario scenario, Handler handler) throws Exception
    {
        server = new Server();
        server.setSessionIdManager(new DefaultSessionIdManager(server));
        HashLoginService authorizationService = new HashLoginService(realm, realmPropsPath.toString());
        ConfigurableSpnegoLoginService loginService = new ConfigurableSpnegoLoginService(realm, AuthorizationService.from(authorizationService, ""));
        loginService.addBean(authorizationService);
        loginService.setKeyTabPath(serviceKeyTabPath);
        loginService.setServiceName(serviceName);
        loginService.setHostName(serviceHost);
        server.addBean(loginService);

        ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
        Constraint constraint = new Constraint();
        constraint.setAuthenticate(true);
        constraint.setRoles(new String[]{"**"}); //allow any authenticated user
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/secure");
        mapping.setConstraint(constraint);
        securityHandler.addConstraintMapping(mapping);
        authenticator = new ConfigurableSpnegoAuthenticator();
        securityHandler.setAuthenticator(authenticator);
        securityHandler.setLoginService(loginService);
        securityHandler.setHandler(handler);

        SessionHandler sessionHandler = new SessionHandler();
        sessionHandler.setHandler(securityHandler);
        start(scenario, sessionHandler);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testPasswordSPNEGOAuthentication(Scenario scenario) throws Exception
    {
        testSPNEGOAuthentication(scenario, false);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testKeyTabSPNEGOAuthentication(Scenario scenario) throws Exception
    {
        testSPNEGOAuthentication(scenario, true);
    }

    private void testSPNEGOAuthentication(Scenario scenario, boolean useKeyTab) throws Exception
    {
        startSPNEGO(scenario, new EmptyServerHandler());
        authenticator.setAuthenticationDuration(Duration.ZERO);

        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());

        // Request without Authentication causes a 401
        Request request = client.newRequest(uri).path("/secure");
        ContentResponse response = request.timeout(15, TimeUnit.SECONDS).send();
        assertNotNull(response);
        assertEquals(401, response.getStatus());

        // Add authentication.
        SPNEGOAuthentication authentication = new SPNEGOAuthentication(uri);
        authentication.setUserName(clientName + "@" + realm);
        if (useKeyTab)
            authentication.setUserKeyTabPath(clientKeyTabPath);
        else
            authentication.setUserPassword(clientPassword);
        authentication.setServiceName(serviceName);
        AuthenticationStore authenticationStore = client.getAuthenticationStore();
        authenticationStore.addAuthentication(authentication);

        // Request with authentication causes a 401 (no previous successful authentication) + 200
        request = client.newRequest(uri).path("/secure");
        response = request.timeout(15, TimeUnit.SECONDS).send();
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        // Authentication results for SPNEGO cannot be cached.
        Authentication.Result authnResult = authenticationStore.findAuthenticationResult(uri);
        assertNull(authnResult);

        AtomicInteger requests = new AtomicInteger();
        client.getRequestListeners().add(new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.incrementAndGet();
            }
        });

        // The server has infinite authentication duration, so
        // subsequent requests will be preemptively authorized.
        request = client.newRequest(uri).path("/secure");
        response = request.timeout(15, TimeUnit.SECONDS).send();
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(1, requests.get());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAuthenticationExpiration(Scenario scenario) throws Exception
    {
        startSPNEGO(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(String target, org.eclipse.jetty.server.Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                IO.readBytes(request.getInputStream());
            }
        });
        long timeout = 1000;
        authenticator.setAuthenticationDuration(Duration.ofMillis(timeout));

        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());

        // Add authentication.
        SPNEGOAuthentication authentication = new SPNEGOAuthentication(uri);
        authentication.setUserName(clientName + "@" + realm);
        authentication.setUserPassword(clientPassword);
        authentication.setServiceName(serviceName);
        AuthenticationStore authenticationStore = client.getAuthenticationStore();
        authenticationStore.addAuthentication(authentication);

        AtomicInteger requests = new AtomicInteger();
        client.getRequestListeners().add(new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.incrementAndGet();
            }
        });

        Request request = client.newRequest(uri).path("/secure");
        Response response = request.timeout(15, TimeUnit.SECONDS).send();
        assertEquals(200, response.getStatus());
        // Expect 401 + 200.
        assertEquals(2, requests.get());

        requests.set(0);
        request = client.newRequest(uri).path("/secure");
        response = request.timeout(15, TimeUnit.SECONDS).send();
        assertEquals(200, response.getStatus());
        // Authentication not expired on server, expect 200 only.
        assertEquals(1, requests.get());

        // Let authentication expire.
        Thread.sleep(2 * timeout);

        requests.set(0);
        request = client.newRequest(uri).path("/secure");
        response = request.timeout(15, TimeUnit.SECONDS).send();
        assertEquals(200, response.getStatus());
        // Authentication expired, expect 401 + 200.
        assertEquals(2, requests.get());

        // Let authentication expire again.
        Thread.sleep(2 * timeout);

        requests.set(0);
        ByteArrayInputStream input = new ByteArrayInputStream("hello_world".getBytes(StandardCharsets.UTF_8));
        request = client.newRequest(uri).method("POST").path("/secure").content(new InputStreamContentProvider(input));
        response = request.timeout(15, TimeUnit.SECONDS).send();
        assertEquals(200, response.getStatus());
        // Authentication expired, but POSTs are allowed.
        assertEquals(1, requests.get());
    }
}
