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

package org.eclipse.jetty.websocket.jsr356.server;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.FragmentConfiguration;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlConfiguration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Utility to build out exploded directory WebApps, in the /target/tests/ directory, for testing out servers that use javax.websocket endpoints.
 * <p>
 * This is particularly useful when the WebSocket endpoints are discovered via the javax.websocket annotation scanning.
 */
public class WSServer
{
    private static final Logger LOG = Log.getLogger(WSServer.class);
    private final Path contextDir;
    private final String contextPath;
    private Server server;
    private URI serverUri;
    private ContextHandlerCollection contexts;
    private Path webinf;
    private Path classesDir;

    public WSServer(WorkDir testdir, String contextName)
    {
        this(testdir.getPath(), contextName);
    }

    public WSServer(File testdir, String contextName)
    {
        this(testdir.toPath(), contextName);
    }

    public WSServer(Path testdir, String contextName)
    {
        this.contextDir = testdir.resolve(contextName);
        this.contextPath = "/" + contextName;
        FS.ensureEmpty(contextDir);
    }

    public void copyClass(Class<?> clazz) throws Exception
    {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        String endpointPath = TypeUtil.toClassReference(clazz);
        URL classUrl = cl.getResource(endpointPath);
        assertThat("Class URL for: " + clazz, classUrl, notNullValue());
        File destFile = Paths.get(classesDir.toString(), FS.separators(endpointPath)).toFile();
        FS.ensureDirExists(destFile.getParentFile());
        File srcFile = new File(classUrl.toURI());
        IO.copy(srcFile, destFile);
    }

    public void copyEndpoint(Class<?> endpointClass) throws Exception
    {
        copyClass(endpointClass);
    }

    public void copyWebInf(String testResourceName) throws IOException
    {
        webinf = contextDir.resolve("WEB-INF");
        FS.ensureDirExists(webinf);
        classesDir = webinf.resolve("classes");
        FS.ensureDirExists(classesDir);
        Path webxml = webinf.resolve("web.xml");
        Path testWebXml = MavenTestingUtils.getTestResourcePath(testResourceName);
        IO.copy(testWebXml.toFile(), webxml.toFile());
    }

    public WebAppContext createWebAppContext()
    {
        WebAppContext context = new WebAppContext();
        context.setContextPath(this.contextPath);
        context.setBaseResource(new PathResource(this.contextDir));
        context.setAttribute("org.eclipse.jetty.websocket.jsr356", Boolean.TRUE);

        context.setConfigurations(new Configuration[]{
            new AnnotationConfiguration(),
            new WebXmlConfiguration(),
            new WebInfConfiguration(),
            new PlusConfiguration(),
            new MetaInfConfiguration(),
            new FragmentConfiguration(),
            new EnvConfiguration()
        });

        return context;
    }

    public void createWebInf() throws IOException
    {
        copyWebInf("empty-web.xml");
    }

    public void deployWebapp(WebAppContext webapp) throws Exception
    {
        contexts.addHandler(webapp);
        contexts.manage(webapp);
        webapp.start();
        if (LOG.isDebugEnabled())
        {
            webapp.dump(System.err);
        }
    }

    public void dump()
    {
        server.dumpStdErr();
    }

    public URI getServerBaseURI()
    {
        return serverUri;
    }

    public Server getServer()
    {
        return server;
    }

    public File getWebAppDir()
    {
        return this.contextDir.toFile();
    }

    public void start() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        HandlerCollection handlers = new HandlerCollection();
        contexts = new ContextHandlerCollection();
        handlers.addHandler(contexts);
        server.setHandler(handlers);

        server.start();

        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        serverUri = new URI(String.format("ws://%s:%d%s/", host, port, contextPath));
        if (LOG.isDebugEnabled())
            LOG.debug("Server started on {}", serverUri);
    }

    public void stop()
    {
        if (server != null)
        {
            try
            {
                server.stop();
            }
            catch (Exception e)
            {
                e.printStackTrace(System.err);
            }
        }
    }
}
