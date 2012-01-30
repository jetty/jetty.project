package org.eclipse.jetty.start;

import static org.hamcrest.Matchers.*;
import org.junit.Assert;
import org.junit.Test;

public class CommandLineBuilderTest {
	@Test
	public void testQuotingSimple() {
		assertQuoting("/opt/jetty", "/opt/jetty");
	}

	@Test
	public void testQuotingSpaceInPath() {
		assertQuoting("/opt/jetty 7/home", "\"/opt/jetty 7/home\"");
	}

	@Test
	public void testQuotingSpaceAndQuotesInPath() {
		assertQuoting("/opt/jetty 7 \"special\"/home", "\"/opt/jetty 7 \\\"special\\\"/home\"");
	}

	private void assertQuoting(String raw, String expected) {
		String actual = CommandLineBuilder.quote(raw);
		Assert.assertThat("Quoted version of [" + raw + "]", actual, is(expected));
	}
}
