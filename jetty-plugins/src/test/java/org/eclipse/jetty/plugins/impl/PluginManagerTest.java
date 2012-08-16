// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.plugins.impl;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.plugins.MavenService;
import org.eclipse.jetty.plugins.model.Plugin;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

/* ------------------------------------------------------------ */
/**
 */
@RunWith(MockitoJUnitRunner.class)
public class PluginManagerTest {
	@Mock
	private MavenService _mavenService;

	private PluginManagerImpl _pluginManager;

	private List<String> availablePlugins = createAvailablePluginsTestData();
	private ClassLoader _classLoader = this.getClass().getClassLoader();
	private String _tmpDir;
	private File _javaTmpDir = new File(System.getProperty("java.io.tmpdir"));

	/* ------------------------------------------------------------ */
	/**
	 * @throws java.lang.Exception
	 */
	@Before
	public void setUp() throws Exception {
		URL resource = this.getClass().getResource("/jetty_home");
		_tmpDir = resource.getFile();
		_pluginManager = new PluginManagerImpl(_mavenService, _tmpDir);
	}

	@Test
	public void testListAvailablePlugins() {
		when(_mavenService.listAvailablePlugins()).thenReturn(availablePlugins);
		List<String> availablePlugins = _pluginManager.listAvailablePlugins();
		assertThat("jetty-jmx not found",
				availablePlugins.contains("jetty-jmx"), is(true));
		assertThat("jetty-jta not found",
				availablePlugins.contains("jetty-jta"), is(true));
	}

	@Test
	public void testInstallPluginJar() {
		String pluginName = "jetty-plugin-with-plugin-jar";
		String pluginJar = _classLoader.getResource("example-plugin.jar")
				.getFile();
		File pluginJarFile = new File(pluginJar);
		Plugin plugin = createTestPlugin(pluginName, pluginJarFile);

		when(_mavenService.getPlugin(pluginName)).thenReturn(plugin);

		_pluginManager.installPlugin(pluginName);

		File someJar = new File(_tmpDir + File.separator + "lib"
				+ File.separator + "someJar.jar");
		assertThat("someJar.jar does not exist", someJar.exists(), is(true));
		File someOtherJar = new File(_tmpDir + File.separator + "lib"
				+ File.separator + "someOtherJar.jar");
		assertThat("someOtherJar.jar does not exist", someOtherJar.exists(),
				is(true));
	}

	@Test
	public void testInstallPlugins() throws IOException {

		String pluginName = "jetty-jmx";
		String jmxPluginConfigJar = _classLoader.getResource(
				"jetty-jmx-7.6.0.v20120127-plugin.jar").getFile();
		File jmxPluginConfigJarFile = new File(jmxPluginConfigJar);

		// Need to copy it to a temp file since the implementation will move the
		// file and we need to keep the test files where they are.
		File jmxPluginConfigTempCopy = copyToTempFile(jmxPluginConfigJarFile);

		Plugin plugin = new Plugin(pluginName, jmxPluginConfigTempCopy);

		when(_mavenService.getPlugin(pluginName)).thenReturn(plugin);

		_pluginManager.installPlugin(pluginName);

		File metaInf = new File(_tmpDir + File.separator + "META-INF");
		File jettyXmlConfigFile = new File(_tmpDir + File.separator + "start.d"
				+ File.separator + "20-jetty-jmx.xml");
		File jettyJmxJarFile = new File(_tmpDir + File.separator + "lib"
				+ File.separator + "jetty-jmx-7.6.0.v20120127.jar");
		assertThat("META-INF should be skipped", metaInf.exists(), not(true));
		assertThat("20-jetty-jmx.xml does not exist",
				jettyXmlConfigFile.exists(), is(true));
		assertThat("jetty-jmx-7.6.0.v20120127.jar does not exist",
				jettyJmxJarFile.exists(), is(true));
	}

	public File copyToTempFile(File sourceFile) throws IOException {
		File destFile = new File(_javaTmpDir + File.separator
				+ sourceFile.getName());
		FileChannel source = null;
		FileChannel destination = null;
		try {
			source = new FileInputStream(sourceFile).getChannel();
			destination = new FileOutputStream(destFile).getChannel();
			destination.transferFrom(source, 0, source.size());
		} finally {
			if (source != null) {
				source.close();
			}
			if (destination != null) {
				destination.close();
			}
		}
		return destFile;
	}

	private List<String> createAvailablePluginsTestData() {
		List<String> availablePlugins = new ArrayList<String>();
		availablePlugins.add("jetty-jmx");
		availablePlugins.add("jetty-jta");
		return availablePlugins;
	}

	private Plugin createTestPlugin(String name, File jar) {
		Plugin plugin = new Plugin(name, jar);
		return plugin;
	}

}
