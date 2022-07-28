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

package org.eclipse.jetty.ee10.demos;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Test the configuration found in WEB-INF/web.xml for purposes of the demo-base
 */
public class ProxyWebAppTest
{
    private Server server;
    private HttpClient client;

    @BeforeEach
    public void setup() throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        WebAppContext webapp = new WebAppContext();
        // This is a pieced together WebApp.
        // We don't have a valid WEB-INF/lib to rely on at this point.
        // So, open up server classes here, for purposes of this testcase.
        webapp.getServerClassMatcher().add("-org.eclipse.jetty.ee10.proxy.");
        webapp.setWar(MavenTestingUtils.getProjectDirPath("src/main/webapp").toString());
        webapp.setExtraClasspath(MavenTestingUtils.getTargetPath().resolve("test-classes").toString());
        server.setHandler(webapp);

        server.start();

        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void teardown()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @Test
    @Tag("external")
    public void testProxyRequest() throws InterruptedException, ExecutionException, TimeoutException
    {
        ContentResponse response = client.newRequest(server.getURI().resolve("/proxy/current/"))
            .followRedirects(false)
            .send();

        // Expecting a 200 OK (not a 302 redirect or other error)
        // If we got an error here, that means our configuration in web.xml is bad / out of date.
        // Such as the redirect from the eclipse website, we want all of the requests to go through
        // this proxy configuration, not redirected to the actual website.
        assertThat("response status", response.getStatus(), is(HttpStatus.OK_200));
        // Expecting a Javadoc / APIDoc response - look for something unique for APIdoc.
        assertThat("response", response.getContentAsString(), containsString("All&nbsp;Classes"));
    }
}
