package org.eclipse.jetty.plugins.util;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class RepositoryParserTest {

	@Before
	public void setUp() throws Exception {
	}

	@Test
	public void testParseLinksInDirectoryListing() throws IOException {
		String listing = StreamUtils.inputStreamToString(this.getClass().getClassLoader().getResourceAsStream("mavenRepoJettyDirectoryListing.html"));
		List<String> modules = RepositoryParser.parseLinksInDirectoryListing(listing);
		assertThat("At least ten jetty modules expected",modules.size(), greaterThan(10));
		assertThat("jetty-jmx module expected", modules.contains("jetty-jmx"), is(true));
	}
	
	@Test
	public void testIsPlugin() throws IOException{
		String listing = StreamUtils.inputStreamToString(this.getClass().getClassLoader().getResourceAsStream("mavenRepoJettyJMXDirectoryListing.html"));
		assertThat("listing describes a plugin", RepositoryParser.isModuleAPlugin(listing), is(true));
		String nonPluginListing = StreamUtils.inputStreamToString(this.getClass().getClassLoader().getResourceAsStream("mavenRepoJettyJNDIDirectoryListing.html"));
		assertThat("listing doesn't describe a plugin", RepositoryParser.isModuleAPlugin(nonPluginListing), is(false));
	}
	
}
