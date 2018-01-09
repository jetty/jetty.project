//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.test;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppLifeCycle;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.providers.WebAppProvider;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.webapp.Configuration;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class DeploymentErrorTest
{
    private static Server server;
    private static URI serverURI; // TODO: test that we can not access webapp.
    private static DeploymentManager deploymentManager;

    @BeforeClass
    public static void setUpServer()
    {
        try
        {
            server = new Server();
            ServerConnector connector = new ServerConnector(server);
            connector.setPort(0);
            server.addConnector(connector);

            // Empty handler collections
            ContextHandlerCollection contexts = new ContextHandlerCollection();
            HandlerCollection handlers = new HandlerCollection();
            handlers.setHandlers(new Handler[]
                    { contexts, new DefaultHandler() });
            server.setHandler(handlers);

            // Deployment Manager
            deploymentManager = new DeploymentManager();
            deploymentManager.setContexts(contexts);
            Path testClasses = MavenTestingUtils.getTargetPath("test-classes");
            System.setProperty("maven.test.classes", testClasses.toAbsolutePath().toString());
            Path docroots = MavenTestingUtils.getTestResourcePathDir("docroots");
            System.setProperty("test.docroots", docroots.toAbsolutePath().toString());
            WebAppProvider appProvider = new WebAppProvider();
            appProvider.setMonitoredDirResource(new PathResource(docroots.resolve("deployerror")));
            appProvider.setScanInterval(1);
            deploymentManager.addAppProvider(appProvider);
            server.addBean(deploymentManager);

            // Setup Configurations
            Configuration.ClassList classlist = Configuration.ClassList
                    .setServerDefault(server);
            classlist.addAfter(
                    "org.eclipse.jetty.webapp.FragmentConfiguration",
                    "org.eclipse.jetty.plus.webapp.EnvConfiguration",
                    "org.eclipse.jetty.plus.webapp.PlusConfiguration");
            classlist.addBefore(
                    "org.eclipse.jetty.webapp.JettyWebXmlConfiguration",
                    "org.eclipse.jetty.annotations.AnnotationConfiguration");

            server.start();
        }
        catch (final Exception e)
        {
            e.printStackTrace();
        }
    }

    @AfterClass
    public static void tearDownServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testErrorDeploy() throws Exception
    {
        assertThat("app not started", deploymentManager.getApps(AppLifeCycle.STARTED), empty());
        TimeUnit.SECONDS.sleep(3);
        List<App> apps = new ArrayList<>();
        apps.addAll(deploymentManager.getApps());
        assertThat("Apps tracked", apps.size(), is(1));
        App app = apps.get(0);
        assertThat("App.handler.isFailed", app.getContextHandler().isFailed(), is(true));
    }
}
