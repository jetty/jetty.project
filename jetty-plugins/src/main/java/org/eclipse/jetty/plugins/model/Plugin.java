package org.eclipse.jetty.plugins.model;

import java.io.File;

public class Plugin {
	private String name;

	private File pluginJar;

	public Plugin(String name, File configJar) {
		this.name = name;
		this.pluginJar = configJar;
	}
	
	public String getName() {
		return name;
	}

	public File getPluginJar() {
		return pluginJar;
	}
}
