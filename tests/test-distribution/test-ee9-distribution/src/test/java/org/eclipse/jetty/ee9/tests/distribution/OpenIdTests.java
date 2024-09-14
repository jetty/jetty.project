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

package org.eclipse.jetty.ee9.tests.distribution;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.distribution.AbstractJettyHomeTest;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
public class OpenIdTests extends AbstractJettyHomeTest
{
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenIdTests.class);

    @Test
    public void testOpenID() throws Exception
    {
        try (KeycloakContainer container = new KeycloakContainer().withRealmImportFile("keycloak/realm-export.json"))
        {
            container.start();
            Path jettyBase = newTestJettyBaseDirectory();
            String jettyVersion = System.getProperty("jettyVersion");
            JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
                    .jettyVersion(jettyVersion)
                    .jettyBase(jettyBase)
                    .build();

            String[] args1 = {
                    "--create-startd",
                    "--approve-all-licenses",
                    "--add-to-start=http,ee9-webapp,ee9-deploy,ee9-openid"
            };

            String clientId = "jetty-api";
            String clientSecret = "JettyRocks!";
            try (JettyHomeTester.Run run1 = distribution.start(args1))
            {
                assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
                assertEquals(0, run1.getExitValue());

                Path webApp = distribution.resolveArtifact("org.eclipse.jetty.ee9:jetty-ee9-test-openid-webapp:war:" + jettyVersion);
                distribution.installWar(webApp, "test");
                String openIdProvider = container.getAuthServerUrl() + "/realms/jetty";
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

                try (JettyHomeTester.Run run2 = distribution.start(args2))
                {
                    assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));
                    startHttpClient(false);
                    String uri = "http://localhost:" + port + "/test";

                    // Initially not authenticated
                    ContentResponse response = client.GET(uri + "/");
                    assertThat(response.getStatus(), is(HttpStatus.OK_200));
                    String content = response.getContentAsString();
                    assertThat(content, containsString("not authenticated"));

                    // Request to login is success
                    response = client.GET(uri + "/login");
                    assertThat(response.getStatus(), is(HttpStatus.OK_200));
                    content = response.getContentAsString();
                    assertThat(content, containsString("success"));

                    // Now authenticated we can get info
                    response = client.GET(uri + "/");
                    assertThat(response.getStatus(), is(HttpStatus.OK_200));
                    content = response.getContentAsString();
                    assertThat(content, containsString("userId: 123456789"));
                    assertThat(content, containsString("name: Alice"));
                    assertThat(content, containsString("email: Alice@example.com"));

                    // Request to admin page gives 403 as we do not have admin role
                    response = client.GET(uri + "/admin");
                    assertThat(response.getStatus(), is(HttpStatus.FORBIDDEN_403));

                    // We are no longer authenticated after logging out
                    response = client.GET(uri + "/logout");
                    assertThat(response.getStatus(), is(HttpStatus.OK_200));
                    content = response.getContentAsString();
                    assertThat(content, containsString("not authenticated"));

                }
            }
        }
    }
}
