//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.plugins;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jetty.plugins.model.Plugin;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class PluginManagerTest
{
    @Mock
    private MavenService _mavenService;
    private DefaultPluginManager _Default_pluginManager;
    private Set<String> availablePlugins = createAvailablePluginsTestData();
    private ClassLoader _classLoader = this.getClass().getClassLoader();
    private String _tmpDir;
    private File _javaTmpDir = new File(System.getProperty("java.io.tmpdir"));

    @Before
    public void setUp() throws Exception
    {
        URL resource = this.getClass().getResource("/jetty_home");
        _tmpDir = resource.getFile();
        _Default_pluginManager = new DefaultPluginManager(_mavenService, _tmpDir);
    }

    @Test
    public void testListAvailablePlugins()
    {
        when(_mavenService.listAvailablePlugins()).thenReturn(availablePlugins);
        Set<String> availablePlugins = _Default_pluginManager.listAvailablePlugins();
        assertThat("jetty-jmx not found",
                availablePlugins.contains("jetty-jmx"), is(true));
        assertThat("jetty-jta not found",
                availablePlugins.contains("jetty-jta"), is(true));
    }

    @Test
    public void testInstallPluginJar()
    {
        String pluginName = "jetty-plugin-with-plugin-zip";
        URL resource = _classLoader.getResource("example-plugin.zip");
        Assert.assertNotNull(resource);
        String pluginZip = resource.getFile();
        File pluginZipFile = new File(pluginZip);
        Plugin plugin = createTestPlugin(pluginName, pluginZipFile);

        when(_mavenService.getPlugin(pluginName)).thenReturn(plugin);

        _Default_pluginManager.installPlugin(pluginName);

        File someJar = new File(_tmpDir + File.separator + "lib" + File.separator + "somejar.jar");
        assertThat("someJar.jar does not exist", someJar.exists(), is(true));
        File someOtherJar = new File(_tmpDir + File.separator + "lib"
                + File.separator + "someotherjar.jar");
        assertThat("someOtherJar.jar does not exist", someOtherJar.exists(),
                is(true));
    }

    @Test
    public void testInstallPlugins() throws IOException
    {
        String pluginName = "jetty-jmx";
        URL resource = _classLoader.getResource("jetty-jmx-version-plugin.zip");
        Assert.assertNotNull(resource);
        String jmxPluginZip = resource.getFile();
        File jmxPluginZipFile = new File(jmxPluginZip);

        // Need to copy it to a temp file since the implementation will move the
        // file and we need to keep the test files where they are.
        File jmxPluginConfigTempCopy = copyToTempFile(jmxPluginZipFile);

        Plugin plugin = new Plugin(pluginName, jmxPluginConfigTempCopy);

        when(_mavenService.getPlugin(pluginName)).thenReturn(plugin);

        _Default_pluginManager.installPlugin(pluginName);

        File metaInf = new File(_tmpDir + File.separator + "META-INF");
        File jettyXmlConfigFile = new File(_tmpDir + File.separator + "start.d"
                + File.separator + "20-jetty-jmx.xml");
        File jettyJmxJarFile = new File(_tmpDir + File.separator + "lib"
                + File.separator + "jetty-jmx-version.jar");
        assertThat("META-INF should be skipped", metaInf.exists(), not(true));
        assertThat("20-jetty-jmx.xml does not exist",
                jettyXmlConfigFile.exists(), is(true));
        assertThat("jetty-jmx-version.jar does not exist",
                jettyJmxJarFile.exists(), is(true));
    }

    public File copyToTempFile(File sourceFile) throws IOException
    {
        File destFile = new File(_javaTmpDir + File.separator + sourceFile.getName());
        try (FileChannel destination = new FileOutputStream(destFile).getChannel();
             FileChannel source = new FileInputStream(sourceFile).getChannel())
        {
            destination.transferFrom(source, 0, source.size());
        }
        return destFile;
    }

    private Set<String> createAvailablePluginsTestData()
    {
        Set<String> availablePlugins = new HashSet<>();
        availablePlugins.add("jetty-jmx");
        availablePlugins.add("jetty-jta");
        return availablePlugins;
    }

    private Plugin createTestPlugin(String name, File jar)
    {
        return new Plugin(name, jar);
    }
}
