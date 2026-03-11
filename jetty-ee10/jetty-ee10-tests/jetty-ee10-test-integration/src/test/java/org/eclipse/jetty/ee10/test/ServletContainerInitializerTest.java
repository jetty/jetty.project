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

package org.eclipse.jetty.ee10.test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class ServletContainerInitializerTest
{
    private Server server;
    private HttpClient client;
    private ServerConnector connector;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        WebAppContext context = new WebAppContext();
        Path war = MavenPaths.targetDir().resolve("webapps").resolve("jetty-ee10-test-sci-webapp.war");
        assertTrue(Files.isRegularFile(war), "Missing war file: " + war);
        context.setWar(war.toString());

        server.setHandler(context);
        server.start();

        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void tearDownServer()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @Test
    public void testAbsoluteOrdering() throws InterruptedException, ExecutionException, TimeoutException
    {
        // This loads a webapp with a SCI which sets an attribute on the servlet context.
        // The servlet in the webapp will respond to requests by printing the value of that attribute.
        // This way we can tell if the SCI was run.
        // The webapp also has an absolute ordering element in web.xml, so this tests we can still load the SCI with absolute ordering.
        URI uri = URI.create("http://localhost:" + connector.getLocalPort());
        ContentResponse response = client.GET(uri);
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), containsString("attribute: true"));
    }
}