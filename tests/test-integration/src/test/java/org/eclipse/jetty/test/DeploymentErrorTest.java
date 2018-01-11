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

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.providers.WebAppProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.webapp.AbstractConfiguration;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class DeploymentErrorTest
{
    private static Server server;
    private static DeploymentManager deploymentManager;
    private static ContextHandlerCollection contexts;

    @BeforeClass
    public static void setUpServer()
    {
        try
        {
            server = new Server();
            ServerConnector connector = new ServerConnector(server);
            connector.setPort(0);
            server.addConnector(connector);

            // Empty contexts collections
            contexts = new ContextHandlerCollection();

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

            // Server handlers
            HandlerCollection handlers = new HandlerCollection();
            handlers.setHandlers(new Handler[]
                    {contexts, new DefaultHandler() });
            server.setHandler(handlers);

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

            // Tracking Config
            classlist.addBefore("org.eclipse.jetty.webapp.WebInfConfiguration",
                    TrackedConfiguration.class.getName());

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
    public void testErrorDeploy_ThrowUnavailableTrue() throws Exception
    {
        List<App> apps = new ArrayList<>();
        apps.addAll(deploymentManager.getApps());
        assertThat("Apps tracked", apps.size(), is(2));
        String contextPath = "/badapp";
        App app = findApp(contextPath, apps);
        ContextHandler context = app.getContextHandler();
        assertThat("ContextHandler.isStarted", context.isStarted(), is(false));
        assertThat("ContextHandler.isFailed", context.isFailed(), is(true));
        assertThat("ContextHandler.isAvailable", context.isAvailable(), is(false));
        WebAppContext webapp = (WebAppContext) context;
        TrackedConfiguration trackedConfiguration = null;
        for(Configuration webappConfig: webapp.getConfigurations())
        {
            if(webappConfig instanceof TrackedConfiguration)
                trackedConfiguration = (TrackedConfiguration) webappConfig;
        }
        assertThat("webapp TrackedConfiguration exists", trackedConfiguration, notNullValue());
        assertThat("trackedConfig.preConfigureCount", trackedConfiguration.preConfigureCounts.get(contextPath), is(1));
        assertThat("trackedConfig.configureCount", trackedConfiguration.configureCounts.get(contextPath), is(1));
        // NOTE: Failure occurs during configure, so postConfigure never runs.
        assertThat("trackedConfig.postConfigureCount", trackedConfiguration.postConfigureCounts.get(contextPath), nullValue());

        assertHttpState(contextPath, HttpStatus.NOT_FOUND_404);
    }

    @Test
    public void testErrorDeploy_ThrowUnavailableFalse() throws Exception
    {
        List<App> apps = new ArrayList<>();
        apps.addAll(deploymentManager.getApps());
        assertThat("Apps tracked", apps.size(), is(2));
        String contextPath = "/badapp-uaf";
        App app = findApp(contextPath, apps);
        ContextHandler context = app.getContextHandler();
        assertThat("ContextHandler.isStarted", context.isStarted(), is(true));
        assertThat("ContextHandler.isFailed", context.isFailed(), is(false));
        assertThat("ContextHandler.isAvailable", context.isAvailable(), is(false));
        WebAppContext webapp = (WebAppContext) context;
        TrackedConfiguration trackedConfiguration = null;
        for(Configuration webappConfig: webapp.getConfigurations())
        {
            if(webappConfig instanceof TrackedConfiguration)
                trackedConfiguration = (TrackedConfiguration) webappConfig;
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

    @Test
    public void testContextHandlerCollection()
    {
        Handler handlers[] = contexts.getHandlers();
        assertThat("ContextHandlerCollection.Handlers.length", handlers.length, is(2));

        // Verify that both handlers are unavailable
        for(Handler handler: handlers)
        {
            assertThat("Handler", handler, instanceOf(ContextHandler.class));
            ContextHandler contextHandler = (ContextHandler) handler;
            assertThat("ContextHandler.isAvailable", contextHandler.isAvailable(), is(false));
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

        private void incrementCount(WebAppContext context, Map<String, Integer> contextCounts)
        {
            Integer count = contextCounts.get(context.getContextPath());
            if(count == null)
            {
                count = new Integer(0);
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
}
