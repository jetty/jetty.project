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

package org.eclipse.jetty.deploy.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.PathMatchers;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Allows for setting up a Jetty server for testing based on XML configuration files.
 */
public class XmlConfiguredJetty
{
    private static final Logger LOG = LoggerFactory.getLogger(XmlConfiguredJetty.class);
    private final List<Resource> _xmlConfigurations = new ArrayList<>();
    private final Map<String, String> _properties = new HashMap<>();
    private Server _server;
    private ContextHandlerCollection _contexts;
    private int _serverPort;
    private String _scheme = HttpScheme.HTTP.asString();
    private Path _jettyBase;

    public XmlConfiguredJetty(Path testDir) throws IOException
    {
        FS.ensureEmpty(testDir);
        _jettyBase = testDir.resolve("base");
        FS.ensureDirExists(_jettyBase);

        setProperty("jetty.base", _jettyBase.toString());

        Path logsDir = _jettyBase.resolve("logs");
        FS.ensureDirExists(logsDir);

        Path webappsDir = _jettyBase.resolve("webapps");
        FS.ensureEmpty(webappsDir);
        setProperty("jetty.deploy.monitoredDir", webappsDir.toString());
        setProperty("jetty.deploy.scanInterval", "1");

        Path etcDir = _jettyBase.resolve("etc");
        FS.ensureDirExists(etcDir);

        Files.copy(MavenPaths.findTestResourceFile("etc/realm.properties"), etcDir.resolve("realm.properties"));
        Files.copy(MavenPaths.findTestResourceFile("etc/webdefault.xml"), etcDir.resolve("webdefault.xml"));

        Path tmpDir = _jettyBase.resolve("tmp");
        FS.ensureEmpty(tmpDir);
        System.setProperty("java.io.tmpdir", tmpDir.toString());
        setProperty("jetty.deploy.tempDir", tmpDir.toString());
    }

    public void addConfiguration(Path xmlConfigFile)
    {
        addConfiguration(ResourceFactory.root().newResource(xmlConfigFile));
    }

    public void addConfiguration(String testConfigName) throws MalformedURLException
    {
        addConfiguration(MavenPaths.findTestResourceFile(testConfigName));
    }

    public void addConfiguration(Resource xmlConfig)
    {
        _xmlConfigurations.add(xmlConfig);
    }
    
    public List<Resource> getConfigurations()
    {
        return Collections.unmodifiableList(_xmlConfigurations);
    }

    public void assertNoContextHandlers()
    {
        int count = _contexts.getHandlers().size();
        assertEquals(0, count, "Should have no Contexts, but saw [%s]".formatted(_contexts.getHandlers().stream().map(Handler::toString).collect(Collectors.joining(", "))));
    }

    public String getResponse(String path) throws IOException
    {
        URI destUri = getServerURI().resolve(path);
        URL url = destUri.toURL();

        URLConnection conn = url.openConnection();
        conn.addRequestProperty("Connection", "close");
        try (InputStream in = conn.getInputStream())
        {
            return IO.toString(in);
        }
    }

    public void assertResponseContains(String path, String needle) throws IOException
    {
        String content = getResponse(path);
        assertThat(content, containsString(needle));
    }

    public void assertContextHandlerExists(String... expectedContextPaths)
    {
        if (expectedContextPaths.length != _contexts.getHandlers().size())
        {
            StringBuilder failure = new StringBuilder();
            failure.append("## Expected Contexts [%d]\n".formatted(expectedContextPaths.length));
            for (String expected : expectedContextPaths)
            {
                failure.append(" - ").append(expected).append('\n');
            }
            failure.append("## Actual Contexts [%d]\n".formatted(_contexts.getHandlers().size()));
            _contexts.getHandlers().forEach((handler) -> failure.append(" - ").append(handler).append('\n'));
            assertEquals(expectedContextPaths.length, _contexts.getHandlers().size(), failure.toString());
        }

        for (String expectedPath : expectedContextPaths)
        {
            boolean found = false;
            for (Handler handler : _contexts.getHandlers())
            {
                if (handler instanceof ContextHandler contextHandler)
                {
                    if (contextHandler.getContextPath().equals(expectedPath))
                    {
                        found = true;
                        assertThat("Context[" + contextHandler.getContextPath() + "].state", contextHandler.getState(), is("STARTED"));
                        break;
                    }
                }
            }
            assertTrue(found, "Did not find Expected Context Path " + expectedPath);
        }
    }

    private void copyFile(String type, Path srcFile, Path destFile) throws IOException
    {
        assertThat(srcFile, PathMatchers.isRegularFile());
        Files.copy(srcFile, destFile, StandardCopyOption.REPLACE_EXISTING);
        assertThat(destFile, PathMatchers.isRegularFile());
        System.err.printf("Copy %s: %s%n  To %s: %s%n", type, srcFile, type, destFile);
    }

    public void copyWebapp(String srcName, String destName) throws IOException
    {
        System.err.printf("Copying Webapp: %s -> %s%n", srcName, destName);
        Path srcFile = MavenPaths.findTestResourceFile("webapps/" + srcName);
        Path destFile = _jettyBase.resolve("webapps/" + destName);

        copyFile("Webapp", srcFile, destFile);
    }

    public String getScheme()
    {
        return _scheme;
    }

    public Server getServer()
    {
        return _server;
    }

    public int getServerPort()
    {
        return _serverPort;
    }

    public URI getServerURI() throws UnknownHostException
    {
        StringBuilder uri = new StringBuilder();
        URIUtil.appendSchemeHostPort(uri, getScheme(), InetAddress.getLocalHost().getHostAddress(), getServerPort());
        return URI.create(uri.toString());
    }

    public Path getJettyBasePath()
    {
        return _jettyBase;
    }

    public void load() throws Exception
    {
        Path testConfig = _jettyBase.resolve("xml-configured-jetty.properties");
        setProperty("jetty.deploy.common.properties", testConfig.toString());

        // Write out configuration for use by ConfigurationManager.
        Properties properties = new Properties();
        properties.putAll(_properties);
        try (OutputStream out = Files.newOutputStream(testConfig))
        {
            properties.store(out, "Generated by " + XmlConfiguredJetty.class.getName());
        }

        XmlConfiguration last = null;
        Object[] obj = new Object[_xmlConfigurations.size()];

        // Configure everything
        for (int i = 0; i < _xmlConfigurations.size(); i++)
        {
            Resource config = _xmlConfigurations.get(i);
            XmlConfiguration configuration = new XmlConfiguration(config);
            if (last != null)
                configuration.getIdMap().putAll(last.getIdMap());
            configuration.getProperties().putAll(_properties);
            obj[i] = configuration.configure();
            last = configuration;
        }

        Map<String, Object> ids = last.getIdMap();

        // Test for Server Instance.
        Server server = (Server)ids.get("Server");
        if (server == null)
        {
            throw new Exception("Load failed to configure a " + Server.class.getName());
        }

        this._server = server;
        this._server.setStopTimeout(10);
        this._contexts = (ContextHandlerCollection)ids.get("Contexts");
    }

    public void removeWebapp(String name) throws IOException
    {
        Path webappFile = _jettyBase.resolve("webapps/" + name);
        if (Files.exists(webappFile))
        {
            LOG.info("Removing webapp: {}", webappFile);
            Files.delete(webappFile);
        }
    }

    public void setProperty(String key, String value)
    {
        _properties.put(key, value);
    }

    public void setScheme(String scheme)
    {
        this._scheme = scheme;
    }

    public void start() throws Exception
    {
        assertNotNull(_server, "Server should not be null (failed load?)");

        _server.start();

        // Find the active server port.
        _serverPort = -1;
        Connector[] connectors = _server.getConnectors();
        for (int i = 0; _serverPort < 0 && i < connectors.length; i++)
        {
            if (connectors[i] instanceof NetworkConnector)
            {
                int port = ((NetworkConnector)connectors[i]).getLocalPort();
                if (port > 0)
                    _serverPort = port;
            }
        }

        assertTrue((1 <= this._serverPort) && (this._serverPort <= 65535), "Server Port is between 1 and 65535. Was actually <" + _serverPort + ">");

        // Uncomment to have server start and continue to run (without exiting)
        // System.err.printf("Listening to port %d%n",this.serverPort);
        // server.join();
    }

    public void stop() throws Exception
    {
        _server.stop();
    }
}
