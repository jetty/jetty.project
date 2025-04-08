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

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.FormRequestContent;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.security.siwe.EthereumAuthenticator;
import org.eclipse.jetty.tests.distribution.siwe.EthereumCredentials;
import org.eclipse.jetty.tests.distribution.siwe.SignInWithEthereumGenerator;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.ajax.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
public class SiweTests extends AbstractJettyHomeTest
{
    private final EthereumCredentials _credentials = new EthereumCredentials();

    @Test
    public void testSiwe() throws Exception
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
            "--add-to-start=http,ee10-webapp,ee10-deploy,ee10-annotations,ethereum"
        };

        try (JettyHomeTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path webApp = distribution.resolveArtifact("org.eclipse.jetty.ee10:jetty-ee10-test-siwe-webapp:war:" + jettyVersion);
            distribution.installWar(webApp, "test");
            Files.createDirectory(jettyBase.resolve("etc"));
            Path realmProperties = Files.createFile(jettyBase.resolve("etc/realm.properties"));
            try (FileWriter fw = new FileWriter(realmProperties.toFile()))
            {
                fw.write(_credentials.getAddress() + ":,admin\n");
            }

            int port = Tester.freePort();
            String[] args2 = {
                "jetty.http.port=" + port,
                "jetty.ssl.port=" + port,
                "jetty.server.dumpAfterStart=true",
                "jetty.httpConfig.relativeRedirectAllowed=false"
            };

//            System.setProperty("distribution.debug.port", "5005");
            try (JettyHomeTester.Run run2 = distribution.start(args2))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));
                startHttpClient(false);
                String uri = "http://localhost:" + port + "/test";

                // Initially not authenticated.
                ContentResponse response = client.GET(uri + "/");
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                String content = response.getContentAsString();
                assertThat(content, containsString("not authenticated"));

                // Request to /admin redirects to loginPage.
                client.setFollowRedirects(false);
                response = client.GET(uri + "/admin");
                assertThat(response.getStatus(), is(HttpStatus.FOUND_302));
                assertThat(response.getHeaders().get(HttpHeader.LOCATION), containsString("/login.html"));

                // Fetch a nonce from the server.
                response = client.GET(uri + "/auth/nonce");
                String nonce = parseNonce(response.getContentAsString());
                assertThat(nonce.length(), equalTo(8));

                // Request to authenticate redirects to /admin page.
                FormRequestContent authRequestContent = getAuthRequestContent(port, nonce);
                response = client.POST(uri + "/auth/login").body(authRequestContent).send();
                assertThat(response.getStatus(), is(HttpStatus.SEE_OTHER_303));
                assertThat(response.getHeaders().get(HttpHeader.LOCATION), containsString("/admin"));

                // We can access /admin as user has the admin role.
                response = client.GET(uri + "/admin");
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                content = response.getContentAsString();
                assertThat(content, containsString("adminPage userPrincipal: " + _credentials.getAddress()));

                // We can't access /forbidden as user does not have the correct role.
                response = client.GET(uri + "/forbidden");
                assertThat(response.getStatus(), is(HttpStatus.FORBIDDEN_403));

                // Logout and we can no longer get the userPrincipal.
                client.setFollowRedirects(true);
                response = client.GET(uri + "/logout");
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                content = response.getContentAsString();
                assertThat(content, containsString("not authenticated"));
            }
        }
    }

    private FormRequestContent getAuthRequestContent(int port, String nonce) throws Exception
    {
        EthereumAuthenticator.SignedMessage signedMessage = _credentials.signMessage(
            SignInWithEthereumGenerator.generateMessage(port, _credentials.getAddress(), nonce));
        Fields fields = new Fields();
        fields.add("signature", signedMessage.signature());
        fields.add("message", signedMessage.message());
        return new FormRequestContent(fields);
    }

    @SuppressWarnings("rawtypes")
    private String parseNonce(String responseContent)
    {
        return (String)((Map)new JSON().parse(new JSON.StringSource(responseContent))).get("nonce");
    }
}
