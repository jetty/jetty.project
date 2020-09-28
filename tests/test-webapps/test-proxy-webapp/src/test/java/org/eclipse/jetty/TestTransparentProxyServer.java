//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class TestTransparentProxyServer
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
        webapp.getServerClasspathPattern().add(
            "-org.eclipse.jetty.proxy.",
            "-org.eclipse.jetty.client.",
            "-org.eclipse.jetty.util.ssl.");
        webapp.getSystemClasspathPattern().add(
            "org.eclipse.jetty.proxy.",
            "org.eclipse.jetty.client.",
            "org.eclipse.jetty.util.ss.");
        webapp.setBaseResource(new PathResource(MavenTestingUtils.getProjectDirPath("src/main/webapp")));
        webapp.setExtraClasspath(MavenTestingUtils.getTargetPath().resolve("classes").toString());
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
        assertThat("response status", response.getStatus(), is(HttpStatus.OK_200));
    }
}
