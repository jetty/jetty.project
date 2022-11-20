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

package org.eclipse.jetty.ee10.tests.distribution;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.ee10.tests.distribution.openid.OpenIdProvider;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.distribution.AbstractJettyHomeTest;
import org.eclipse.jetty.tests.hometester.JettyHomeTester;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class OpenIdTests extends AbstractJettyHomeTest
{
    @Test
    public void testOpenID() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--create-startd",
            "--approve-all-licenses",
            "--add-to-start=http,ee10-webapp,ee10-deploy,ee10-openid"
        };

        String clientId = "clientId123";
        String clientSecret = "clientSecret456";
        OpenIdProvider openIdProvider = new OpenIdProvider(clientId, clientSecret);
        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File webApp = distribution.resolveArtifact("org.eclipse.jetty.ee10:jetty-ee10-test-openid-webapp:war:" + jettyVersion);
            distribution.installWarFile(webApp, "test");

            int port = distribution.freePort();
            openIdProvider.addRedirectUri("http://localhost:" + port + "/test/j_security_check");
            openIdProvider.start();
            String[] args2 = {
                "jetty.http.port=" + port,
                "jetty.ssl.port=" + port,
                "jetty.openid.provider=" + openIdProvider.getProvider(),
                "jetty.openid.clientId=" + clientId,
                "jetty.openid.clientSecret=" + clientSecret,
                //"jetty.server.dumpAfterStart=true",
            };

            try (JettyHomeTester.Run run2 = distribution.start(args2))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", 10, TimeUnit.SECONDS));
                startHttpClient(false);
                String uri = "http://localhost:" + port + "/test";
                openIdProvider.setUser(new OpenIdProvider.User("123456789", "Alice"));

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
        finally
        {
            openIdProvider.stop();
        }
    }
}
