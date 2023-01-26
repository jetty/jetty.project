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

package org.eclipse.jetty.ee9.test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppLifeCycle;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.deploy.providers.ContextProvider;
import org.eclipse.jetty.ee9.webapp.AbstractConfiguration;
import org.eclipse.jetty.ee9.webapp.Configuration;
import org.eclipse.jetty.ee9.webapp.Configurations;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.ee9.webapp.WebInfConfiguration;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(WorkDirExtension.class)
public class DeploymentErrorTest
{
    public WorkDir workDir;

    private StacklessLogging stacklessLogging;
    private Server server;
    private DeploymentManager deploymentManager;
    private ContextHandlerCollection contexts;

    public Path startServer(Consumer<Path> docrootSetupConsumer) throws Exception
    {
        stacklessLogging = new StacklessLogging(WebAppContext.class, DeploymentManager.class, NoClassDefFoundError.class);

        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
        
        ResourceFactory resourceFactory = ResourceFactory.of(server);

        // Empty contexts collections
        contexts = new ContextHandlerCollection();

        //Environment
        Environment ee9 = Environment.ensure("ee9");
        ee9.setAttribute("contextHandlerClass", "org.eclipse.jetty.ee9.webapp.WebAppContext");
        
        // Deployment Manager
        deploymentManager = new DeploymentManager();
        deploymentManager.setContexts(contexts);
        Path testClasses = MavenTestingUtils.getTargetPath("test-classes");
        System.setProperty("maven.test.classes", testClasses.toAbsolutePath().toString());

        Path docroots = workDir.getPath();
        FS.ensureEmpty(docroots);

        if (docrootSetupConsumer != null)
        {
            docrootSetupConsumer.accept(docroots);
        }

        System.setProperty("test.docroots", docroots.toAbsolutePath().toString());
        ContextProvider appProvider = new ContextProvider();
        appProvider.setEnvironmentName("ee9");
        appProvider.setMonitoredDirResource(resourceFactory.newResource(docroots));
        appProvider.setScanInterval(1);
        deploymentManager.addAppProvider(appProvider);
        server.addBean(deploymentManager);

        // Server handlers
        server.setHandler(contexts);

        // Setup Configurations
        Configurations.setServerDefault(server)
            .add("org.eclipse.jetty.ee9.plus.webapp.EnvConfiguration",
                "org.eclipse.jetty.ee9.plus.webapp.PlusConfiguration",
                "org.eclipse.jetty.ee9.annotations.AnnotationConfiguration",
                TrackedConfiguration.class.getName()
            );

        server.start();
        return docroots;
    }

    @AfterEach
    public void tearDownServer() throws Exception
    {
        if (stacklessLogging != null)
            stacklessLogging.close();
        server.stop();
    }

    private void copyBadApp(String sourceXml, Path docroots)
    {
        try
        {
            Path deployErrorSrc = MavenTestingUtils.getTestResourcePathDir("docroots/deployerror");
            IO.copy(deployErrorSrc.resolve(sourceXml), docroots.resolve("badapp.xml"));
            Path badappDir = deployErrorSrc.resolve("badapp");
            Path badappDest = docroots.resolve("badapp");
            FS.ensureDirExists(badappDest);
            IO.copyDir(badappDir, badappDest);
        }
        catch (IOException e)
        {
            throw new RuntimeIOException(e);
        }
    }

    /**
     * Test of a server startup, where a DeploymentManager has a WebAppProvider pointing
     * to a directory that already has a webapp that will deploy with an error.
     * The webapp is a WebAppContext with {@code throwUnavailableOnStartupException=true;}.
     */
    @Test
    public void testInitialBadAppUnavailableTrue()
    {
        assertThrows(NoClassDefFoundError.class, () ->
        {
            startServer(docroots -> copyBadApp("badapp.xml", docroots));
        });

        // The above should have prevented the server from starting.
        assertThat("server.isRunning", server.isRunning(), is(false));
    }

    /**
     * Test of a server startup, where a DeploymentManager has a ContextProvider pointing
     * to a directory that already has a webapp that will deploy with an error.
     * The webapp is a WebAppContext with {@code throwUnavailableOnStartupException=false;}.
     */
    @Test
    public void testInitialBadAppUnavailableFalse() throws Exception
    {
        startServer(docroots -> copyBadApp("badapp-unavailable-false.xml", docroots));

        List<App> apps = new ArrayList<>();
        apps.addAll(deploymentManager.getApps());
        assertThat("Apps tracked", apps.size(), is(1));
        String contextPath = "/badapp-uaf";
        App app = findApp(contextPath, apps);
        ContextHandler coreContext = app.getContextHandler();
        org.eclipse.jetty.ee9.nested.ContextHandler contextHandler = null;
        if (coreContext instanceof org.eclipse.jetty.ee9.nested.ContextHandler.CoreContextHandler coreContextHandler)
        {
           contextHandler = coreContextHandler.getContextHandler();
        }
        assertNotNull(contextHandler);
        assertThat("ContextHandler.isStarted", contextHandler.isStarted(), is(true));
        assertThat("ContextHandler.isFailed", contextHandler.isFailed(), is(false));
        assertThat("ContextHandler.isAvailable", contextHandler.isAvailable(), is(false));
        WebAppContext webapp = (WebAppContext)contextHandler;
        TrackedConfiguration trackedConfiguration = null;
        for (Configuration webappConfig : webapp.getConfigurations())
        {
            if (webappConfig instanceof TrackedConfiguration)
                trackedConfiguration = (TrackedConfiguration)webappConfig;
        }
        assertThat("webapp TrackedConfiguration exists", trackedConfiguration, notNullValue());
        assertThat("trackedConfig.preConfigureCount", trackedConfiguration.preConfigureCounts.get(contextPath), is(1));
        assertThat("trackedConfig.configureCount", trackedConfiguration.configureCounts.get(contextPath), is(1));
        // NOTE: Failure occurs during configure, so postConfigure never runs.
        assertThat("trackedConfig.postConfigureCount", trackedConfiguration.postConfigureCounts.get(contextPath), nullValue());

        assertHttpState(contextPath, HttpStatus.SERVICE_UNAVAILABLE_503);
    }

    /**
     * Test of a server startup, where a DeploymentManager has a WebAppProvider pointing
     * to a directory that already has no initial webapps that will deploy.
     * A webapp is added (by filesystem copies) into the monitored docroot.
     * The webapp will have a deployment error.
     * The webapp is a WebAppContext with {@code throwUnavailableOnStartupException=true;}.
     */
    @Test
    public void testDelayedAddBadAppUnavailableTrue() throws Exception
    {
        Path docroots = startServer(null);

        String contextPath = "/badapp";
        AppLifeCycleTrackingBinding startTracking = new AppLifeCycleTrackingBinding(contextPath);
        DeploymentManager deploymentManager = server.getBean(DeploymentManager.class);
        deploymentManager.addLifeCycleBinding(startTracking);

        copyBadApp("badapp.xml", docroots);

        // Wait for deployment manager to do its thing
        assertThat("AppLifeCycle.FAILED event occurred", startTracking.failedLatch.await(3, TimeUnit.SECONDS), is(true));

        List<App> apps = new ArrayList<>();
        apps.addAll(deploymentManager.getApps());
        assertThat("Apps tracked", apps.size(), is(1));
        App app = findApp(contextPath, apps);
        ContextHandler coreContext = app.getContextHandler();
        org.eclipse.jetty.ee9.nested.ContextHandler contextHandler = null;
        if (coreContext instanceof org.eclipse.jetty.ee9.nested.ContextHandler.CoreContextHandler coreContextHandler)
        {
           contextHandler = coreContextHandler.getContextHandler();
        }
        assertNotNull(contextHandler);
        
        assertThat("ContextHandler.isStarted", contextHandler.isStarted(), is(false));
        assertThat("ContextHandler.isFailed", contextHandler.isFailed(), is(true));
        assertThat("ContextHandler.isAvailable", contextHandler.isAvailable(), is(false));
        WebAppContext webapp = (WebAppContext)contextHandler;
        TrackedConfiguration trackedConfiguration = null;
        for (Configuration webappConfig : webapp.getConfigurations())
        {
            if (webappConfig instanceof TrackedConfiguration)
                trackedConfiguration = (TrackedConfiguration)webappConfig;
        }
        assertThat("webapp TrackedConfiguration exists", trackedConfiguration, notNullValue());
        assertThat("trackedConfig.preConfigureCount", trackedConfiguration.preConfigureCounts.get(contextPath), is(1));
        assertThat("trackedConfig.configureCount", trackedConfiguration.configureCounts.get(contextPath), is(1));
        // NOTE: Failure occurs during configure, so postConfigure never runs.
        assertThat("trackedConfig.postConfigureCount", trackedConfiguration.postConfigureCounts.get(contextPath), nullValue());

        assertHttpState(contextPath, HttpStatus.NOT_FOUND_404);
    }

    /**
     * Test of a server startup, where a DeploymentManager has a WebAppProvider pointing
     * to a directory that already has no initial webapps that will deploy.
     * A webapp is added (by filesystem copies) into the monitored docroot.
     * The webapp will have a deployment error.
     * The webapp is a WebAppContext with {@code throwUnavailableOnStartupException=false;}.
     */
    @Test
    public void testDelayedAddBadAppUnavailableFalse() throws Exception
    {
        Path docroots = startServer(null);

        String contextPath = "/badapp-uaf";
        AppLifeCycleTrackingBinding startTracking = new AppLifeCycleTrackingBinding(contextPath);
        DeploymentManager deploymentManager = server.getBean(DeploymentManager.class);
        deploymentManager.addLifeCycleBinding(startTracking);

        copyBadApp("badapp-unavailable-false.xml", docroots);

        // Wait for deployment manager to do its thing
        startTracking.startedLatch.await(3, TimeUnit.SECONDS);

        List<App> apps = new ArrayList<>();
        apps.addAll(deploymentManager.getApps());
        assertThat("Apps tracked", apps.size(), is(1));
        App app = findApp(contextPath, apps);
        ContextHandler coreContext = app.getContextHandler();
        org.eclipse.jetty.ee9.nested.ContextHandler contextHandler = null;
        if (coreContext instanceof org.eclipse.jetty.ee9.nested.ContextHandler.CoreContextHandler coreContextHandler)
        {
           contextHandler = coreContextHandler.getContextHandler();
        }
        assertNotNull(contextHandler);
        
        assertThat("ContextHandler.isStarted", contextHandler.isStarted(), is(true));
        assertThat("ContextHandler.isFailed", contextHandler.isFailed(), is(false));
        assertThat("ContextHandler.isAvailable", contextHandler.isAvailable(), is(false));
        WebAppContext webapp = (WebAppContext)contextHandler;
        TrackedConfiguration trackedConfiguration = null;
        for (Configuration webappConfig : webapp.getConfigurations())
        {
            if (webappConfig instanceof TrackedConfiguration)
                trackedConfiguration = (TrackedConfiguration)webappConfig;
        }
        assertThat("webapp TrackedConfiguration exists", trackedConfiguration, notNullValue());
        assertThat("trackedConfig.preConfigureCount", trackedConfiguration.preConfigureCounts.get(contextPath), is(1));
        assertThat("trackedConfig.configureCount", trackedConfiguration.configureCounts.get(contextPath), is(1));
        // NOTE: Failure occurs during configure, so postConfigure never runs.
        assertThat("trackedConfig.postConfigureCount", trackedConfiguration.postConfigureCounts.get(contextPath), nullValue());

        assertHttpState(contextPath, HttpStatus.SERVICE_UNAVAILABLE_503);
    }

    private void assertHttpState(String contextPath, int expectedStatusCode) throws Exception
    {
        URI destURI = server.getURI().resolve(contextPath);
        HttpClient client = new HttpClient();
        try
        {
            client.start();
            ContentResponse response = client.newRequest(destURI).method(HttpMethod.GET).send();
            assertThat("GET Response: " + destURI, response.getStatus(), is(expectedStatusCode));
        }
        finally
        {
            client.stop();
        }
    }

    private App findApp(String contextPath, List<App> apps)
    {
        for (App app : apps)
        {
            if (contextPath.equals(app.getContextPath()))
                return app;
        }
        return null;
    }

    public static class TrackedConfiguration extends AbstractConfiguration
    {
        public Map<String, Integer> preConfigureCounts = new HashMap<>();
        public Map<String, Integer> configureCounts = new HashMap<>();
        public Map<String, Integer> postConfigureCounts = new HashMap<>();

        public TrackedConfiguration()
        {
            addDependents(WebInfConfiguration.class);
        }

        private void incrementCount(WebAppContext context, Map<String, Integer> contextCounts)
        {
            Integer count = contextCounts.get(context.getContextPath());
            if (count == null)
            {
                count = 0;
            }
            count++;
            contextCounts.put(context.getContextPath(), count);
        }

        @Override
        public void preConfigure(WebAppContext context) throws Exception
        {
            incrementCount(context, preConfigureCounts);
        }

        @Override
        public void configure(WebAppContext context) throws Exception
        {
            incrementCount(context, configureCounts);
        }

        @Override
        public void postConfigure(WebAppContext context) throws Exception
        {
            incrementCount(context, postConfigureCounts);
        }
    }

    public static class AppLifeCycleTrackingBinding implements AppLifeCycle.Binding
    {
        public final CountDownLatch startingLatch = new CountDownLatch(1);
        public final CountDownLatch startedLatch = new CountDownLatch(1);
        public final CountDownLatch failedLatch = new CountDownLatch(1);
        private final String expectedContextPath;

        public AppLifeCycleTrackingBinding(String expectedContextPath)
        {
            this.expectedContextPath = expectedContextPath;
        }

        @Override
        public String[] getBindingTargets()
        {
            return new String[]{AppLifeCycle.STARTING, AppLifeCycle.STARTED, AppLifeCycle.FAILED};
        }

        @Override
        public void processBinding(Node node, App app)
        {
            if (app.getContextPath().equalsIgnoreCase(expectedContextPath))
            {
                if (node.getName().equalsIgnoreCase(AppLifeCycle.STARTING))
                {
                    startingLatch.countDown();
                }
                else if (node.getName().equalsIgnoreCase(AppLifeCycle.STARTED))
                {
                    startedLatch.countDown();
                }
                else if (node.getName().equalsIgnoreCase(AppLifeCycle.FAILED))
                {
                    failedLatch.countDown();
                }
            }
        }
    }
}
