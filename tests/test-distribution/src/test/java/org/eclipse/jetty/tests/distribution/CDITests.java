package org.eclipse.jetty.tests.distribution;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CDITests extends AbstractDistributionTest
{
    /**
     * Tests a WAR file that is CDI complete as it includes the weld
     * library in its WEB-INF/lib directory.
     *
     * @throws Exception
     */
    @Test
    public void testCDI2_IncludedInWebapp() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--create-startd",
            "--approve-all-licenses",
            "--add-to-start=http,deploy,annotations,jsp"
        };
        try (DistributionTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-cdi2-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "demo");

            distribution.installBaseResource("cdi/demo_context.xml", "webapps/demo.xml");

            int port = distribution.freePort();
            try (DistributionTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started @", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/demo/greetings");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                // Confirm Servlet based CDI
                assertThat(response.getContentAsString(), containsString("Hello GreetingsServlet"));
                // Confirm Listener based CDI (this has been a problem in the past, keep this for regression testing!)
                assertThat(response.getHeaders().get("Server"), containsString("CDI-Demo-org.eclipse.jetty.test"));

                run2.stop();
                assertTrue(run2.awaitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    /**
     * Tests a WAR file that is expects CDI to be provided by the server.
     *
     * <p>
     *     This means the WAR does NOT have the weld libs in its
     *     WEB-INF/lib directory.
     * </p>
     *
     * <p>
     *     The expectation is that CDI2 is provided by weld
     *     and the javax.el support comes from JSP
     * </p>
     *
     * @throws Exception
     */
    @Test
    public void testCDI2_ProvidedByServer() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--create-startd",
            "--approve-all-licenses",
            // standard entries
            "--add-to-start=http,deploy,annotations",
            // cdi2 specific entry (should transitively pull in what it needs, the user should not be expected to know the transitive entries)
            "--add-to-start=cdi2"
        };
        try (DistributionTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-cdi2-webapp:war:" + jettyVersion);
            Path demoDir = distribution.installWarFile(war, "demo");
            // Remove weld libs
            Path libDir = demoDir.resolve("WEB-INF/lib");
            List<Path> weldLibs = Files.list(libDir).filter((path) -> path.getFileName().toString().contains("weld"))
                .collect(Collectors.toList());
            for (Path weldLib : weldLibs)
            {
                assertTrue(Files.deleteIfExists(weldLib));
            }

            distribution.installBaseResource("cdi/demo_context.xml", "webapps/demo.xml");

            int port = distribution.freePort();
            try (DistributionTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started @", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/demo/greetings");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                // Confirm Servlet based CDI
                assertThat(response.getContentAsString(), containsString("Hello GreetingsServlet"));
                // Confirm Listener based CDI (this has been a problem in the past, keep this for regression testing!)
                assertThat(response.getHeaders().get("Server"), containsString("CDI-Demo-org.eclipse.jetty.test"));

                run2.stop();
                assertTrue(run2.awaitFor(5, TimeUnit.SECONDS));
            }
        }
    }
}
