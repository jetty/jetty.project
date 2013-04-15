//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.*;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.Assert;

/**
 * Utility to build out exploded directory WebApps, in the /target/tests/ directory, for testing out servers that use javax.websocket endpoints.
 * <p>
 * This is particularly useful when the WebSocket endpoints are discovered via the javax.websocket annotation scanning.
 */
public class WSServer
{
    @SuppressWarnings("unused")
    private final TestingDir testdir;
    private final File contextDir;
    private final String contextPath;
    private Server server;
    private URI serverUri;
    private ContextHandlerCollection contexts;
    private File webinf;
    private File classesDir;

    public WSServer(TestingDir testdir, String contextName)
    {
        this.testdir = testdir;
        this.contextDir = testdir.getFile(contextName);
        this.contextPath = "/" + contextName;
        FS.ensureEmpty(contextDir);
    }

    public void copyEndpoint(Class<?> endpointClass) throws Exception
    {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        String endpointPath = endpointClass.getName().replace('.','/') + ".class";
        URL classUrl = cl.getResource(endpointPath);
        Assert.assertThat("Class URL for: " + endpointClass,classUrl,notNullValue());
        File destFile = new File(classesDir,OS.separators(endpointPath));
        FS.ensureDirExists(destFile.getParentFile());
        File srcFile = new File(classUrl.toURI());
        IO.copy(srcFile,destFile);
    }

    public WebAppContext createWebAppContext()
    {
        WebAppContext context = new WebAppContext();
        context.setContextPath(this.contextPath);
        context.setWar(this.contextDir.getAbsolutePath());
        return context;
    }

    public void createWebInf() throws IOException
    {
        webinf = new File(contextDir,"WEB-INF");
        FS.ensureDirExists(webinf);
        classesDir = new File(webinf,"classes");
        FS.ensureDirExists(classesDir);
        File webxml = new File(webinf,"web.xml");
        File emptyWebXml = MavenTestingUtils.getTestResourceFile("empty-web.xml");
        IO.copy(emptyWebXml,webxml);
    }

    public void deployWebapp(WebAppContext webapp) throws Exception
    {
        contexts.addHandler(webapp);
        webapp.start();
    }

    public void dump()
    {
        server.dumpStdErr();
    }

    public URI getServerBaseURI()
    {
        return serverUri;
    }

    public File getWebAppDir()
    {
        return this.contextDir;
    }

    public void start() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
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
        serverUri = new URI(String.format("ws://%s:%d/",host,port));
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
