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

package org.eclipse.jetty.tests.distribution;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each test enables only the {@code <env>-jaspi} module, as the documentation tells an operator to,
 * so the {@code AuthConfigFactory} comes from that module's own dependency on the factory module.
 */
public class JaspiModuleTests extends AbstractJettyHomeTest
{
    /**
     * A web application that knows nothing about Jakarta Authentication must be unaffected by
     * the jaspi module being enabled on the server it is deployed to.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ee10", "ee11"})
    public void testWebAppWithNoAuthConfigProvider(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        String[] modules = {
            "http",
            toEnvironment("deploy", env),
            toEnvironment("webapp", env),
            toEnvironment("jaspi", env)
        };

        try (JettyHomeTester.Run runConfig = distribution.start("--add-modules=" + String.join(",", modules)))
        {
            assertTrue(runConfig.awaitForStart());
            assertEquals(0, runConfig.getExitValue(), logs(runConfig));

            Path war = distribution.resolveArtifact("org.eclipse.jetty.demos:jetty-servlet5-demo-simple-webapp:war:" + jettyVersion);
            distribution.installWar(war, "test");

            int httpPort = Tester.freePort();
            try (JettyHomeTester.Run runStart = distribution.start("jetty.http.port=" + httpPort))
            {
                assertTrue(runStart.awaitForJettyStart(), logs(runStart));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + httpPort + "/test/hello/");
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("HelloWorld"));
            }
        }
    }

    /**
     * The jaspi-demo module registers a {@code ServerAuthModule} doing HTTP BASIC authentication
     * for the /test context, so a constrained resource there is challenged by that module and is
     * served once credentials the demo realm knows are presented.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ee8", "ee9", "ee10", "ee11"})
    public void testJaspiDemoAuthenticatesConstrainedResource(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        String[] modules = {
            "http",
            "demo-realm",
            toEnvironment("deploy", env),
            toEnvironment("webapp", env),
            toEnvironment("jaspi", env),
            toEnvironment("jaspi-demo", env)
        };

        try (JettyHomeTester.Run runConfig = distribution.start("--add-modules=" + String.join(",", modules)))
        {
            assertTrue(runConfig.awaitForStart());
            assertEquals(0, runConfig.getExitValue(), logs(runConfig));

            // The demo module registers for the /test context, so the web application is deployed there with a constraint but no login-config.
            Path webapp = jettyBase.resolve("webapps/test");
            Files.createDirectories(webapp.resolve("WEB-INF"));
            Files.writeString(webapp.resolve("index.html"), "JASPI protected content", StandardCharsets.UTF_8);
            Files.writeString(webapp.resolve("WEB-INF/web.xml"), constrainedWebXml(env), StandardCharsets.UTF_8);

            int httpPort = Tester.freePort();
            try (JettyHomeTester.Run runStart = distribution.start("jetty.http.port=" + httpPort))
            {
                assertTrue(runStart.awaitForJettyStart(), logs(runStart));

                startHttpClient();
                String uri = "http://localhost:" + httpPort + "/test/index.html";

                ContentResponse response = client.GET(uri);
                assertEquals(HttpStatus.UNAUTHORIZED_401, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getHeaders().get(HttpHeader.WWW_AUTHENTICATE), containsString("Basic realm=\"Test Realm\""));

                String credentials = Base64.getEncoder().encodeToString("plain:plain".getBytes(StandardCharsets.UTF_8));
                response = client.newRequest(uri)
                    .headers(headers -> headers.put(HttpHeader.AUTHORIZATION, "Basic " + credentials))
                    .send();
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("JASPI protected content"));
            }
        }
    }

    /**
     * A web application can register its own {@code ServerAuthModule} from a
     * {@code ServletContextListener}, which requires it to be able to load the Jakarta
     * Authentication API from the server.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ee10", "ee11"})
    public void testWebAppRegistersItsOwnServerAuthModule(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        String[] modules = {
            "http",
            toEnvironment("deploy", env),
            toEnvironment("webapp", env),
            toEnvironment("jaspi", env)
        };

        try (JettyHomeTester.Run runConfig = distribution.start("--add-modules=" + String.join(",", modules)))
        {
            assertTrue(runConfig.awaitForStart());
            assertEquals(0, runConfig.getExitValue(), logs(runConfig));

            Path war = distribution.resolveArtifact("org.eclipse.jetty.ee:jetty-ee-test-jaspi-webapp:war:" + jettyVersion);
            distribution.installWar(war, "test");

            int httpPort = Tester.freePort();
            try (JettyHomeTester.Run runStart = distribution.start("jetty.http.port=" + httpPort))
            {
                assertTrue(runStart.awaitForJettyStart(), logs(runStart));

                startHttpClient();
                String uri = "http://localhost:" + httpPort + "/test/index.html";

                // The module the web application registered is the one challenging for the request.
                ContentResponse response = client.GET(uri);
                assertEquals(HttpStatus.UNAUTHORIZED_401, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getHeaders().get(HttpHeader.WWW_AUTHENTICATE), containsString("TestHeader"));

                response = client.newRequest(uri)
                    .headers(headers -> headers.put("X-Test-User", "someone"))
                    .send();
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("JASPI protected content"));
            }
        }
    }

    /**
     * Two environments can have jaspi enabled in the same distribution. Each has its own
     * {@code AuthConfigFactory}, so the ee9 context is authenticated by the module the ee9
     * demo registers for it and the ee10 context by the one its web application registers.
     */
    @Test
    public void testTwoEnvironmentsWithJaspiEnabled() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        String[] modules = {
            "http",
            "demo-realm",
            toEnvironment("deploy", "ee9"),
            toEnvironment("webapp", "ee9"),
            toEnvironment("jaspi", "ee9"),
            toEnvironment("jaspi-demo", "ee9"),
            toEnvironment("deploy", "ee10"),
            toEnvironment("webapp", "ee10"),
            toEnvironment("jaspi", "ee10")
        };

        try (JettyHomeTester.Run runConfig = distribution.start("--add-modules=" + String.join(",", modules)))
        {
            assertTrue(runConfig.awaitForStart());
            assertEquals(0, runConfig.getExitValue(), logs(runConfig));

            Path ee9Webapp = jettyBase.resolve("webapps/test");
            Files.createDirectories(ee9Webapp.resolve("WEB-INF"));
            Files.writeString(ee9Webapp.resolve("index.html"), "JASPI protected content", StandardCharsets.UTF_8);
            Files.writeString(ee9Webapp.resolve("WEB-INF/web.xml"), constrainedWebXml("ee9"), StandardCharsets.UTF_8);
            Files.writeString(jettyBase.resolve("webapps/test.properties"), "environment=ee9", StandardCharsets.UTF_8);

            Path war = distribution.resolveArtifact("org.eclipse.jetty.ee:jetty-ee-test-jaspi-webapp:war:" + jettyVersion);
            distribution.installWar(war, "test10");
            Files.writeString(jettyBase.resolve("webapps/test10.properties"), "environment=ee10", StandardCharsets.UTF_8);

            int httpPort = Tester.freePort();
            try (JettyHomeTester.Run runStart = distribution.start("jetty.http.port=" + httpPort))
            {
                assertTrue(runStart.awaitForJettyStart(), logs(runStart));

                startHttpClient();
                String ee9Uri = "http://localhost:" + httpPort + "/test/index.html";

                ContentResponse response = client.GET(ee9Uri);
                assertEquals(HttpStatus.UNAUTHORIZED_401, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getHeaders().get(HttpHeader.WWW_AUTHENTICATE), containsString("Basic realm=\"Test Realm\""));

                String credentials = Base64.getEncoder().encodeToString("plain:plain".getBytes(StandardCharsets.UTF_8));
                response = client.newRequest(ee9Uri)
                    .headers(headers -> headers.put(HttpHeader.AUTHORIZATION, "Basic " + credentials))
                    .send();
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("JASPI protected content"));

                String ee10Uri = "http://localhost:" + httpPort + "/test10/index.html";

                response = client.GET(ee10Uri);
                assertEquals(HttpStatus.UNAUTHORIZED_401, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getHeaders().get(HttpHeader.WWW_AUTHENTICATE), containsString("TestHeader"));

                response = client.newRequest(ee10Uri)
                    .headers(headers -> headers.put("X-Test-User", "someone"))
                    .send();
                assertEquals(HttpStatus.OK_200, response.getStatus(), new ResponseDetails(response));
                assertThat(response.getContentAsString(), containsString("JASPI protected content"));
            }
        }
    }

    /**
     * A descriptor constraining the whole context to the {@code user} role, written to the
     * schema of the given environment, and with no login-config so that the authenticator
     * is left to Jakarta Authentication.
     */
    private static String constrainedWebXml(String env)
    {
        String namespace = "ee8".equals(env) ? "http://xmlns.jcp.org/xml/ns/javaee" : "https://jakarta.ee/xml/ns/jakartaee";
        String version = switch (env)
        {
            case "ee8" -> "4.0";
            case "ee9" -> "5.0";
            default -> "6.0";
        };

        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <web-app xmlns="%1$s"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="%1$s %1$s/web-app_%2$s.xsd"
                     version="%3$s">
              <security-constraint>
                <web-resource-collection>
                  <web-resource-name>Protected</web-resource-name>
                  <url-pattern>/*</url-pattern>
                </web-resource-collection>
                <auth-constraint>
                  <role-name>user</role-name>
                </auth-constraint>
              </security-constraint>
              <security-role>
                <role-name>user</role-name>
              </security-role>
            </web-app>
            """.formatted(namespace, version.replace('.', '_'), version);
    }
}
