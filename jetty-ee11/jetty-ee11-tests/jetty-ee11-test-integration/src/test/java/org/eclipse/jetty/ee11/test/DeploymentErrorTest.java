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

package org.eclipse.jetty.ee11.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.deploy.Deployer;
import org.eclipse.jetty.deploy.DeploymentScanner;
import org.eclipse.jetty.deploy.StandardDeployer;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.webapp.AbstractConfiguration;
import org.eclipse.jetty.ee11.webapp.Configuration;
import org.eclipse.jetty.ee11.webapp.Configurations;
import org.eclipse.jetty.ee11.webapp.WebAppContext;
import org.eclipse.jetty.ee11.webapp.WebInfConfiguration;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class DeploymentErrorTest
{
    public WorkDir workDir;

    private StacklessLogging stacklessLogging;
    private Server server;
    private StandardDeployer deployer;

    public Path startServer(Consumer<Path> docrootSetupConsumer) throws Exception
    {
        stacklessLogging = new StacklessLogging(WebAppContext.class, StandardDeployer.class, NoClassDefFoundError.class);

        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        // Empty contexts collections
        ContextHandlerCollection contexts = new ContextHandlerCollection();

        // Deployment Manager
        deployer = new StandardDeployer(contexts);
        Path testClasses = MavenTestingUtils.getTargetPath("test-classes");
        System.setProperty("maven.test.classes", testClasses.toAbsolutePath().toString());

        Path docroots = workDir.getPath();
        FS.ensureEmpty(docroots);

        if (docrootSetupConsumer != null)
        {
            docrootSetupConsumer.accept(docroots);
        }

        System.setProperty("test.docroots", docroots.toAbsolutePath().toString());
        DeploymentScanner deploymentScanner = new DeploymentScanner(server, deployer);
        assertNotNull(ServletContextHandler.ENVIRONMENT, "Expected environment does not exist");
        DeploymentScanner.EnvironmentConfig envConfig = deploymentScanner.configureEnvironment("ee11");
        envConfig.setDefaultContextHandlerClass(WebAppContext.class);
        deploymentScanner.setScanInterval(1);
        deploymentScanner.addWebappsDirectory(docroots);
        server.addBean(deploymentScanner);

        server.addBean(deployer);

        // Server handlers
        server.setHandler(new Handler.Sequence(contexts, new DefaultHandler()));

        // Setup Configurations
        Configurations.setServerDefault(server)
            .add("org.eclipse.jetty.ee11.plus.webapp.EnvConfiguration",
                "org.eclipse.jetty.ee11.plus.webapp.PlusConfiguration",
                "org.eclipse.jetty.ee11.annotations.AnnotationConfiguration",
                TrackedConfiguration.class.getName()
            );

        server.start();
        return docroots;
    }

    @AfterEach
    public void tearDownServer()
    {
        if (stacklessLogging != null)
            stacklessLogging.close();
        LifeCycle.stop(server);
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
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Test of a server startup, where a Deployer has a WebAppProvider pointing
     * to a directory that already has a webapp that will deploy with an error.
     * The webapp is a WebAppContext with {@code throwUnavailableOnStartupException=true;}.
     */
    @Test
    public void testInitialBadAppUnavailableTrue()
    {
        assertThrows(NoClassDefFoundError.class, () -> startServer(docroots -> copyBadApp("badapp.xml", docroots)));

        // The above should have prevented the server from starting.
        assertThat("server.isRunning", server.isRunning(), is(false));
    }

    /**
     * Test of a server startup, where a Deployer has a WebAppProvider pointing
     * to a directory that already has a webapp that will deploy with an error.
     * The webapp is a WebAppContext with {@code throwUnavailableOnStartupException=false;}.
     */
    @Test
    public void testInitialBadAppUnavailableFalse() throws Exception
    {
        startServer(docroots -> copyBadApp("badapp-unavailable-false.xml", docroots));

        List<ContextHandler> contexts = getContextHandlers(deployer);
        assertThat("Contexts tracked", contexts.size(), is(1));
        String contextPath = "/badapp-uaf";
        ContextHandler context = findContext(contextPath, contexts);
        assertNotNull(context);
        assertThat("ContextHandler.isStarted", context.isStarted(), is(true));
        assertThat("ContextHandler.isFailed", context.isFailed(), is(false));
        assertThat("ContextHandler.isAvailable", context.isAvailable(), is(false));
        WebAppContext webapp = (WebAppContext)context;
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
     * Test of a server startup, where a Deployer has a WebAppProvider pointing
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
        AppLifeCycleTracking startTracking = new AppLifeCycleTracking(contextPath);
        StandardDeployer deployer = server.getBean(StandardDeployer.class);
        deployer.addEventListener(startTracking);

        copyBadApp("badapp.xml", docroots);

        // Wait for deployment manager to do its thing
        assertThat("ContextHandlerLifeCycle.FAILED event occurred", startTracking.failedLatch.await(3, TimeUnit.SECONDS), is(true));

        List<ContextHandler> apps = getContextHandlers(deployer);
        assertThat("Contexts tracked", apps.size(), is(1));
        ContextHandler context = findContext(contextPath, apps);
        assertNotNull(context);
        assertThat("ContextHandler.isStarted", context.isStarted(), is(false));
        assertThat("ContextHandler.isFailed", context.isFailed(), is(true));
        assertThat("ContextHandler.isAvailable", context.isAvailable(), is(false));
        WebAppContext webapp = (WebAppContext)context;
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
     * Test of a server startup, where a Deployer has a WebAppProvider pointing
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
        AppLifeCycleTracking startTracking = new AppLifeCycleTracking(contextPath);
        StandardDeployer deployer = server.getBean(StandardDeployer.class);
        deployer.addEventListener(startTracking);

        copyBadApp("badapp-unavailable-false.xml", docroots);

        // Wait for deployment manager to do its thing
        assertTrue(startTracking.startedLatch.await(3, TimeUnit.SECONDS));

        List<ContextHandler> apps = getContextHandlers(this.deployer);
        assertThat("Contexts tracked", apps.size(), is(1));
        ContextHandler context = findContext(contextPath, apps);
        assertNotNull(context);
        assertThat("ContextHandler.isStarted", context.isStarted(), is(true));
        assertThat("ContextHandler.isFailed", context.isFailed(), is(false));
        assertThat("ContextHandler.isAvailable", context.isAvailable(), is(false));
        WebAppContext webapp = (WebAppContext)context;
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
        try (HttpClient client = new HttpClient())
        {
            client.start();
            ContentResponse response = client.newRequest(destURI).method(HttpMethod.GET).send();
            assertThat("GET Response: " + destURI, response.getStatus(), is(expectedStatusCode));
        }
    }

    private List<ContextHandler> getContextHandlers(StandardDeployer deployer)
    {
        return deployer.getContexts().getHandlers().stream()
            .filter(h -> (h instanceof ContextHandler))
            .map(ContextHandler.class::cast)
            .toList();
    }

    private ContextHandler findContext(String contextPath, List<ContextHandler> apps)
    {
        for (ContextHandler contextHandler : apps)
        {
            if (contextPath.equals(contextHandler.getContextPath()))
                return contextHandler;
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
            super(new Builder().addDependents(WebInfConfiguration.class));
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
        public void preConfigure(WebAppContext context)
        {
            incrementCount(context, preConfigureCounts);
        }

        @Override
        public void configure(WebAppContext context) throws Exception
        {
            incrementCount(context, configureCounts);
        }

        @Override
        public void postConfigure(WebAppContext context)
        {
            incrementCount(context, postConfigureCounts);
        }
    }

    public static class AppLifeCycleTracking implements Deployer.Listener
    {
        public final CountDownLatch startingLatch = new CountDownLatch(1);
        public final CountDownLatch startedLatch = new CountDownLatch(1);
        public final CountDownLatch failedLatch = new CountDownLatch(1);
        private final String expectedContextPath;

        public AppLifeCycleTracking(String expectedContextPath)
        {
            this.expectedContextPath = expectedContextPath;
        }

        @Override
        public void onStarting(ContextHandler contextHandler)
        {
            if (contextHandler.getContextPath().equalsIgnoreCase(expectedContextPath))
                startingLatch.countDown();
        }

        @Override
        public void onStarted(ContextHandler contextHandler)
        {
            if (contextHandler.getContextPath().equalsIgnoreCase(expectedContextPath))
                startedLatch.countDown();
        }

        @Override
        public void onFailure(ContextHandler contextHandler, Throwable cause)
        {
            if (contextHandler.getContextPath().equalsIgnoreCase(expectedContextPath))
                failedLatch.countDown();
        }
    }
}
