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

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.gargoylesoftware.htmlunit.FailingHttpStatusCodeException;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.html.HtmlSubmitInput;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OpenIdTests extends AbstractJettyHomeTest
{
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenIdTests.class);

    private static final KeycloakContainer KEYCLOAK_CONTAINER = new KeycloakContainer();

    private static final String clientId = "jetty-api";
    private static final String clientSecret = "JettyRocks!";

    private static final String userName = "jetty";
    private static final String password = "JettyRocks!Really";

    private static final String firstName = "John";
    private static final String lastName = "Doe";
    private static final String email = "jetty@jetty.org";

    private static String userId;

    @BeforeAll
    public static void startKeycloak()
    {
        KEYCLOAK_CONTAINER.start();
        // init keycloak
        try (Keycloak keycloak = KEYCLOAK_CONTAINER.getKeycloakAdminClient())
        {
            RealmRepresentation jettyRealm = new RealmRepresentation();
            jettyRealm.setId("jetty");
            jettyRealm.setRealm("jetty");
            jettyRealm.setEnabled(true);
            keycloak.realms().create(jettyRealm);

            ClientRepresentation clientRepresentation = new ClientRepresentation();
            clientRepresentation.setClientId(clientId);
            clientRepresentation.setSecret(clientSecret);
            clientRepresentation.setRedirectUris(List.of("http://localhost:*"));
            clientRepresentation.setEnabled(true);
            clientRepresentation.setPublicClient(Boolean.TRUE);
            keycloak.realm("jetty").clients().create(clientRepresentation);

            UserRepresentation user = new UserRepresentation();
            user.setEnabled(true);
            user.setFirstName(firstName);
            user.setLastName(lastName);
            user.setUsername(userName);
            user.setEmail(email);

            userId = CreatedResponseUtil.getCreatedId(keycloak.realm("jetty").users().create(user));

            CredentialRepresentation passwordCred = new CredentialRepresentation();
            passwordCred.setTemporary(false);
            passwordCred.setType(CredentialRepresentation.PASSWORD);
            passwordCred.setValue(password);

            // Set password credential
            keycloak.realm("jetty").users().get(userId).resetPassword(passwordCred);
        }
    }

    @AfterAll
    public static void stopKeycloak()
    {
        if (KEYCLOAK_CONTAINER.isRunning())
        {
            KEYCLOAK_CONTAINER.stop();
        }
    }

    public static Stream<Arguments> tests()
    {
        return Stream.of(
                Arguments.of("ee9", "ee9-openid"),
                Arguments.of("ee10", "openid")
        );
    }

    @ParameterizedTest
    @MethodSource("tests")
    public void testOpenID(String env, String openIdModule) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
                .jettyVersion(jettyVersion)
                .jettyBase(jettyBase)
                .build();

        String[] args1 = {
                "--create-startd",
                "--approve-all-licenses",
                "--add-to-start=http," + toEnvironment("webapp", env) + "," + toEnvironment("deploy", env) + "," + openIdModule
        };

        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path webApp = distribution.resolveArtifact("org.eclipse.jetty." + env + ":jetty-" + env + "-test-openid-webapp:war:" + jettyVersion);
            distribution.installWar(webApp, "test");
            String openIdProvider = KEYCLOAK_CONTAINER.getAuthServerUrl() + "/realms/jetty";
            LOGGER.info("openIdProvider: {}", openIdProvider);

            int port = Tester.freePort();
            String[] args2 = {
                    "jetty.http.port=" + port,
                    "jetty.ssl.port=" + port,
                    "jetty.openid.provider=" + openIdProvider,
                    "jetty.openid.clientId=" + clientId,
                    "jetty.openid.clientSecret=" + clientSecret,
                    //"jetty.server.dumpAfterStart=true",
            };

            try (JettyHomeTester.Run run2 = distribution.start(args2); WebClient webClient = new WebClient();)
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));
                String uri = "http://localhost:" + port + "/test";
                // Initially not authenticated
                HtmlPage page = webClient.getPage(uri + "/");
                assertThat(page.getWebResponse().getStatusCode(), is(HttpStatus.OK_200));
                String content = page.getWebResponse().getContentAsString();
                assertThat(content, containsString("not authenticated"));
                // Request to login is success
                page = webClient.getPage(uri + "/login");
                assertThat(page.getWebResponse().getStatusCode(), is(HttpStatus.OK_200));
                // redirect to openid provider login form
                HtmlForm htmlForm = page.getForms().get(0);
                htmlForm.getInputByName("username").setValue(userName);
                htmlForm.getInputByName("password").setValue(password);

                HtmlSubmitInput submit = htmlForm.getOneHtmlElementByAttribute(
                        "input", "type", "submit");
                page = submit.click();
                assertThat(page.getWebResponse().getStatusCode(), is(HttpStatus.OK_200));
                assertThat(page.getWebResponse().getContentAsString(), containsString("success"));

                // Now authenticated we can get info
                page = webClient.getPage(uri + "/");
                assertThat(page.getWebResponse().getStatusCode(), is(HttpStatus.OK_200));
                content = page.getWebResponse().getContentAsString();
                assertThat(content, containsString("userId: " + userId));
                assertThat(content, containsString("name: " + firstName + " " + lastName));
                assertThat(content, containsString("email: " + email));

                // Request to admin page gives 403 as we do not have admin role
                FailingHttpStatusCodeException failingHttpStatusCodeException =
                        assertThrows(FailingHttpStatusCodeException.class, () -> webClient.getPage(uri + "/admin"));
                assertThat(failingHttpStatusCodeException.getStatusCode(), is(HttpStatus.FORBIDDEN_403));

                // We are no longer authenticated after logging out
                page = webClient.getPage(uri + "/logout");
                assertThat(page.getWebResponse().getStatusCode(), is(HttpStatus.OK_200));
                content = page.getWebResponse().getContentAsString();
                assertThat(content, containsString("not authenticated"));

            }
        }
    }
}
