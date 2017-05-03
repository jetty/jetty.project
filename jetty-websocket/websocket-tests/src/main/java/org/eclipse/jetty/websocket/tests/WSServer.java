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

package org.eclipse.jetty.websocket.tests;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.JAR;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.FragmentConfiguration;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.Parser;

/**
 * Utility to build out exploded directory WebApps, in the /target/tests/ directory, for testing out servers that use javax.websocket endpoints.
 * <p>
 * This is particularly useful when the WebSocket endpoints are discovered via the javax.websocket annotation scanning.
 */
public class WSServer implements LocalFuzzer.Provider
{
    private static final Logger LOG = Log.getLogger(WSServer.class);
    private final Path contextDir;
    private final String contextPath;
    private final ByteBufferPool bufferPool = new MappedByteBufferPool();
    private Server server;
    private URI serverUri;
    private ContextHandlerCollection contexts;
    private LocalConnector localConnector;
    private Path webinf;
    private Path classesDir;
    
    public WSServer(TestingDir testdir, String contextName)
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
        String endpointPath = clazz.getName().replace('.', '/') + ".class";
        URL classUrl = cl.getResource(endpointPath);
        assertThat("Class URL for: " + clazz, classUrl, notNullValue());
        Path destFile = classesDir.resolve(endpointPath);
        FS.ensureDirExists(destFile.getParent());
        File srcFile = new File(classUrl.toURI());
        IO.copy(srcFile, destFile.toFile());
    }
    
    public void copyEndpoint(Class<?> endpointClass) throws Exception
    {
        copyClass(endpointClass);
    }
    
    public void copyLib(Class<?> clazz, String jarFileName) throws URISyntaxException, IOException
    {
        webinf = contextDir.resolve("WEB-INF");
        FS.ensureDirExists(webinf);
        Path libDir = webinf.resolve("lib");
        FS.ensureDirExists(libDir);
        Path jarFile = libDir.resolve(jarFileName);
        
        URL codeSourceURL = clazz.getProtectionDomain().getCodeSource().getLocation();
        assertThat("Class CodeSource URL is file scheme", codeSourceURL.getProtocol(), is("file"));
        
        File sourceCodeSourceFile = new File(codeSourceURL.toURI());
        if (sourceCodeSourceFile.isDirectory())
        {
            LOG.info("Creating " + jarFile + " from " + sourceCodeSourceFile);
            JAR.create(sourceCodeSourceFile, jarFile.toFile());
        }
        else
        {
            LOG.info("Copying " + sourceCodeSourceFile + " to " + jarFile);
            IO.copy(sourceCodeSourceFile, jarFile.toFile());
        }
    }
    
    public void copyWebInf(String testResourceName) throws IOException
    {
        webinf = contextDir.resolve("WEB-INF");
        FS.ensureDirExists(webinf);
        classesDir = webinf.resolve("classes");
        FS.ensureDirExists(classesDir);
        Path webxml = webinf.resolve("web.xml");
        File testWebXml = MavenTestingUtils.getTestResourceFile(testResourceName);
        IO.copy(testWebXml, webxml.toFile());
    }
    
    public WebAppContext createWebAppContext() throws IOException
    {
        WebAppContext context = new WebAppContext();
        context.setContextPath(this.contextPath);
        context.setBaseResource(new PathResource(this.contextDir));
        context.setAttribute("org.eclipse.jetty.websocket.jsr356", Boolean.TRUE);
        
        // @formatter:off
        context.setConfigurations(new Configuration[]{
                new AnnotationConfiguration(),
                new WebXmlConfiguration(),
                new WebInfConfiguration(),
                new PlusConfiguration(),
                new MetaInfConfiguration(),
                new FragmentConfiguration(),
                new EnvConfiguration()});
        // @formatter:on
        
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
        webapp.setThrowUnavailableOnStartupException(true);
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
    
    public LocalConnector getLocalConnector()
    {
        return localConnector;
    }
    
    @Override
    public Parser newClientParser(Parser.Handler parserHandler)
    {
        return new Parser(WebSocketPolicy.newClientPolicy(), bufferPool, parserHandler);
    }
    
    @Override
    public LocalConnector.LocalEndPoint newLocalConnection()
    {
        return getLocalConnector().connect();
    }
    
    public LocalFuzzer newLocalFuzzer() throws Exception
    {
        return new LocalFuzzer(this);
    }

    public LocalFuzzer newLocalFuzzer(CharSequence requestPath) throws Exception
    {
        return new LocalFuzzer(this, requestPath);
    }
    
    public LocalFuzzer newLocalFuzzer(CharSequence requestPath, Map<String,String> upgradeRequest) throws Exception
    {
        return new LocalFuzzer(this, requestPath, upgradeRequest);
    }
    
    public URI getServerBaseURI()
    {
        return serverUri;
    }
    
    public Server getServer()
    {
        return server;
    }
    
    public Path getWebAppDir()
    {
        return this.contextDir;
    }
    
    public void start() throws Exception
    {
        server = new Server();
        
        // Main network connector
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
    
        // Add Local Connector
        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);
        
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
        if (server == null)
        {
            return;
        }
        
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
