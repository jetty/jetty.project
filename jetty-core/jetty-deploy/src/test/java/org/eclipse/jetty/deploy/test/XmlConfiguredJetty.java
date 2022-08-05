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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.PathAssert;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.xml.XmlConfiguration;

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
    private List<Resource> _xmlConfigurations;
    private Map<String, String> _properties = new HashMap<>();
    private Server _server;
    private int _serverPort;
    private String _scheme = HttpScheme.HTTP.asString();
    private File _jettyHome;

    public static Server loadConfigurations(List<Resource> configurations, Map<String, String> properties)
        throws Exception
    {
        XmlConfiguration last = null;
        Object[] obj = new Object[configurations.size()];

        // Configure everything
        for (int i = 0; i < configurations.size(); i++)
        {
            Resource config = configurations.get(i);
            XmlConfiguration configuration = new XmlConfiguration(config);
            if (last != null)
                configuration.getIdMap().putAll(last.getIdMap());
            configuration.getProperties().putAll(properties);
            obj[i] = configuration.configure();
            last = configuration;
        }

        // Test for Server Instance.
        Server foundServer = null;
        int serverCount = 0;
        for (int i = 0; i < configurations.size(); i++)
        {
            if (obj[i] instanceof Server)
            {
                if (obj[i].equals(foundServer))
                {
                    // Identical server instance found
                    break;
                }
                foundServer = (Server)obj[i];
                serverCount++;
            }
        }

        if (serverCount <= 0)
        {
            throw new Exception("Load failed to configure a " + Server.class.getName());
        }

        assertEquals(1, serverCount, "Server load count");

        return foundServer;
    }

    public XmlConfiguredJetty(Path testdir) throws IOException
    {
        _xmlConfigurations = new ArrayList<>();
        Properties properties = new Properties();

        String jettyHomeBase = testdir.toString();
        // Ensure we have a new (pristene) directory to work with.
        int idx = 0;
        _jettyHome = new File(jettyHomeBase + "--" + idx);
        while (_jettyHome.exists())
        {
            idx++;
            _jettyHome = new File(jettyHomeBase + "--" + idx);
        }
        deleteContents(_jettyHome);
        // Prepare Jetty.Home (Test) dir
        _jettyHome.mkdirs();

        File logsDir = new File(_jettyHome, "logs");
        logsDir.mkdirs();

        File etcDir = new File(_jettyHome, "etc");
        etcDir.mkdirs();
        IO.copyFile(MavenTestingUtils.getTestResourceFile("etc/realm.properties"), new File(etcDir, "realm.properties"));
        IO.copyFile(MavenTestingUtils.getTestResourceFile("etc/webdefault.xml"), new File(etcDir, "webdefault.xml"));

        File webappsDir = new File(_jettyHome, "webapps");
        if (webappsDir.exists())
        {
            deleteContents(webappsDir);
        }
        webappsDir.mkdirs();

        File tmpDir = new File(_jettyHome, "tmp");
        if (tmpDir.exists())
        {
            deleteContents(tmpDir);
        }
        tmpDir.mkdirs();

        File workishDir = new File(_jettyHome, "workish");
        if (workishDir.exists())
        {
            deleteContents(workishDir);
        }
        workishDir.mkdirs();

        // Setup properties
        System.setProperty("java.io.tmpdir", tmpDir.getAbsolutePath());
        properties.setProperty("jetty.home", _jettyHome.getAbsolutePath());
        System.setProperty("jetty.home", _jettyHome.getAbsolutePath());
        properties.setProperty("test.basedir", MavenTestingUtils.getBaseDir().getAbsolutePath());
        properties.setProperty("test.resourcesdir", MavenTestingUtils.getTestResourcesDir().getAbsolutePath());
        properties.setProperty("test.webapps", webappsDir.getAbsolutePath());
        properties.setProperty("test.targetdir", MavenTestingUtils.getTargetDir().getAbsolutePath());
        properties.setProperty("test.workdir", workishDir.getAbsolutePath());

        // Write out configuration for use by ConfigurationManager.
        File testConfig = new File(_jettyHome, "xml-configured-jetty.properties");
        try (OutputStream out = new FileOutputStream(testConfig))
        {
            properties.store(out, "Generated by " + XmlConfiguredJetty.class.getName());
        }
        for (Object key : properties.keySet())
        {
            setProperty(String.valueOf(key), String.valueOf(properties.get(key)));
        }
    }

    public void addConfiguration(File xmlConfigFile)
    {
        addConfiguration(ResourceFactory.root().newResource(xmlConfigFile.toPath()));
    }

    public void addConfiguration(String testConfigName) throws MalformedURLException
    {
        addConfiguration(MavenTestingUtils.getTestResourceFile(testConfigName));
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
        List<ContextHandler> contexts = getContextHandlers();
        if (contexts.size() > 0)
        {
            for (ContextHandler context : contexts)
            {
                System.err.println("WebAppContext should not exist:\n" + context);
            }
            assertEquals(0, contexts.size(), "Contexts.size");
        }
    }

    public String getResponse(String path) throws IOException
    {
        URI destUri = getServerURI().resolve(path);
        URL url = destUri.toURL();

        URLConnection conn = url.openConnection();
        conn.addRequestProperty("Connection", "close");
        InputStream in = null;
        try
        {
            in = conn.getInputStream();
            return IO.toString(in);
        }
        finally
        {
            IO.close(in);
        }
    }

    public void assertResponseContains(String path, String needle) throws IOException
    {
        String content = getResponse(path);
        assertThat(content, containsString(needle));
    }

    public void assertContextHandlerExists(String... expectedContextPaths)
    {
        List<ContextHandler> contexts = getContextHandlers();
        if (expectedContextPaths.length != contexts.size())
        {
            System.err.println("## Expected Contexts");
            for (String expected : expectedContextPaths)
            {
                System.err.println(expected);
            }
            System.err.println("## Actual Contexts");
            for (ContextHandler context : contexts)
            {
                System.err.printf("%s ## %s%n", context.getContextPath(), context);
            }
            assertEquals(expectedContextPaths.length, contexts.size(), "Contexts.size");
        }

        for (String expectedPath : expectedContextPaths)
        {
            boolean found = false;
            for (ContextHandler context : contexts)
            {
                if (context.getContextPath().equals(expectedPath))
                {
                    found = true;
                    assertThat("Context[" + context.getContextPath() + "].state", context.getState(), is("STARTED"));
                    break;
                }
            }
            assertTrue(found, "Did not find Expected Context Path " + expectedPath);
        }
    }

    private void copyFile(String type, File srcFile, File destFile) throws IOException
    {
        PathAssert.assertFileExists(type + " File", srcFile);
        IO.copyFile(srcFile, destFile);
        PathAssert.assertFileExists(type + " File", destFile);
        System.err.printf("Copy %s: %s%n  To %s: %s%n", type, srcFile, type, destFile);
        System.err.printf("Destination Exists: %s - %s%n", destFile.exists(), destFile);
    }

    public void copyWebapp(String srcName, String destName) throws IOException
    {
        System.err.printf("Copying Webapp: %s -> %s%n", srcName, destName);
        File srcDir = MavenTestingUtils.getTestResourceDir("webapps");
        File destDir = new File(_jettyHome, "webapps");

        File srcFile = new File(srcDir, srcName);
        File destFile = new File(destDir, destName);

        copyFile("Webapp", srcFile, destFile);
    }

    private void deleteContents(File dir)
    {
        // System.err.printf("Delete  (dir) %s/%n",dir);
        if (!dir.exists())
        {
            return;
        }

        File[] files = dir.listFiles();
        if (files != null)
        {
            for (File file : files)
            {
                // Safety measure. only recursively delete within target directory.
                if (file.isDirectory() && file.getAbsolutePath().contains("target" + File.separator))
                {
                    deleteContents(file);
                    assertTrue(file.delete(), "Delete failed: " + file.getAbsolutePath());
                }
                else
                {
                    assertTrue(file.delete(), "Delete failed: " + file.getAbsolutePath());
                }
            }
        }
    }

    public File getJettyDir(String name)
    {
        return new File(_jettyHome, name);
    }

    public File getJettyHome()
    {
        return _jettyHome;
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

    public List<ContextHandler> getContextHandlers()
    {
        List<ContextHandler> contexts = new ArrayList<>();
        ContextHandlerCollection handlers = (ContextHandlerCollection)_server.getHandler();
        List<Handler> children = handlers.getHandlers();

        for (Handler handler : children)
        {
            if (handler instanceof ContextHandler)
            {
                ContextHandler context = (ContextHandler)handler;
                contexts.add(context);
            }
        }

        return contexts;
    }

    public void load() throws Exception
    {
        this._server = loadConfigurations(_xmlConfigurations, _properties);
        this._server.setStopTimeout(10);
    }

    public void removeWebapp(String name)
    {
        File destDir = new File(_jettyHome, "webapps");
        File contextFile = new File(destDir, name);
        if (contextFile.exists())
        {
            assertTrue(contextFile.delete(), "Delete of Webapp file: " + contextFile.getAbsolutePath());
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
