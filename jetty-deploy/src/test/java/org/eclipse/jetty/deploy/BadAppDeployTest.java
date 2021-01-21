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

package org.eclipse.jetty.deploy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.servlet.ServletException;

import org.eclipse.jetty.deploy.providers.WebAppProvider;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.time.Duration.ofSeconds;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class BadAppDeployTest
{
    public WorkDir workDir;
    private Server server;

    @AfterEach
    public void stopServer() throws Exception
    {
        if (server != null)
        {
            server.stop();
        }
    }

    @Test
    public void testBadAppThrowOnUnavailableTrueXmlOrder() throws Exception
    {
        /* Non-working Bean Order as reported in Issue #3620
           It is important that this Order be maintained for an accurate test case.
           ### BEAN: QueuedThreadPool[qtp1327763628]@4f2410ac{STOPPED,8<=0<=200,i=0,r=-1,q=0}[NO_TRY]
           ### BEAN: ServerConnector@16f65612{HTTP/1.1,[http/1.1]}{0.0.0.0:8080}
           ### BEAN: HandlerCollection@5f150435{STOPPED}
           ### BEAN: DeploymentManager@1c53fd30{STOPPED}
         */

        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        HandlerCollection handlers = new HandlerCollection();
        handlers.addHandler(contexts);
        handlers.addHandler(new DefaultHandler());
        server.setHandler(handlers); // this should be done before addBean(deploymentManager)

        DeploymentManager deploymentManager = new DeploymentManager();
        deploymentManager.setContexts(contexts);
        WebAppProvider webAppProvider = new WebAppProvider();
        deploymentManager.addAppProvider(webAppProvider);

        Path webappsDir = workDir.getEmptyPathDir().resolve("webapps").toAbsolutePath();

        FS.ensureDirExists(webappsDir);

        copyTestResource("webapps/badapp/badapp.war", webappsDir.resolve("badapp.war"));
        copyTestResource("webapps/badapp/badapp.xml", webappsDir.resolve("badapp.xml"));

        webAppProvider.setMonitoredDirName(webappsDir.toString());
        webAppProvider.setScanInterval(1);

        server.addBean(deploymentManager); // this should be done after setHandler(handlers)

        assertTimeoutPreemptively(ofSeconds(10), () ->
        {

            try (StacklessLogging ignore = new StacklessLogging(Log.getLogger(WebAppContext.class),
                Log.getLogger(DeploymentManager.class),
                Log.getLogger("org.eclipse.jetty.server.handler.ContextHandler.badapp")))
            {
                ServletException cause = assertThrows(ServletException.class, () -> server.start());
                assertThat(cause.getMessage(), containsString("intentionally"));
                assertTrue(server.isFailed(), "Server should be in failed state");
            }
        });
    }

    @Test
    public void testBadAppThrowOnUnavailableTrueEmbeddedOrder() throws Exception
    {
        /* Working Bean Order
           ### BEAN: QueuedThreadPool[qtp1530388690]@5b37e0d2{STOPPED,8<=0<=200,i=0,r=-1,q=0}[NO_TRY]
           ### BEAN: ServerConnector@5e265ba4{HTTP/1.1,[http/1.1]}{0.0.0.0:8080}
           ### BEAN: DeploymentManager@3419866c{STOPPED}
           ### BEAN: HandlerCollection@63e31ee{STOPPED}
         */

        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ContextHandlerCollection contexts = new ContextHandlerCollection();

        DeploymentManager deploymentManager = new DeploymentManager();
        deploymentManager.setContexts(contexts);
        WebAppProvider webAppProvider = new WebAppProvider();
        deploymentManager.addAppProvider(webAppProvider);

        Path webappsDir = workDir.getEmptyPathDir().resolve("webapps").toAbsolutePath();

        FS.ensureDirExists(webappsDir);

        copyTestResource("webapps/badapp/badapp.war", webappsDir.resolve("badapp.war"));
        copyTestResource("webapps/badapp/badapp.xml", webappsDir.resolve("badapp.xml"));

        webAppProvider.setMonitoredDirName(webappsDir.toString());
        webAppProvider.setScanInterval(1);

        server.addBean(deploymentManager); // this should be done before setHandler(handlers)

        HandlerCollection handlers = new HandlerCollection();
        handlers.addHandler(contexts);
        handlers.addHandler(new DefaultHandler());
        server.setHandler(handlers); // this should be done after addBean(deploymentManager)

        assertTimeoutPreemptively(ofSeconds(10), () ->
        {

            try (StacklessLogging ignore = new StacklessLogging(Log.getLogger(WebAppContext.class),
                Log.getLogger(DeploymentManager.class),
                Log.getLogger("org.eclipse.jetty.server.handler.ContextHandler.badapp")))
            {
                ServletException cause = assertThrows(ServletException.class, () -> server.start());
                assertThat(cause.getMessage(), containsString("intentionally"));
                assertTrue(server.isFailed(), "Server should be in failed state");
            }
        });
    }

    private void copyTestResource(String testResourceFile, Path webappsFile) throws IOException
    {
        Path srcFile = MavenTestingUtils.getTestResourcePathFile(testResourceFile);
        Files.copy(srcFile, webappsFile);
    }
}
