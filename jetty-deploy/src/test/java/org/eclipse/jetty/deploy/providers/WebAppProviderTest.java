package org.eclipse.jetty.deploy.providers;

import java.io.File;
import java.util.Arrays;

import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class WebAppProviderTest
{
    private static XmlConfiguredJetty jetty;

    @BeforeClass
    public static void setupEnvironment() throws Exception
    {
        jetty = new XmlConfiguredJetty();
        jetty.addConfiguration("jetty.xml");
        jetty.addConfiguration("jetty-deploy-wars.xml");

        // Setup initial context
        jetty.copyContext("foo.xml","foo.xml");
        jetty.copyWebapp("foo-webapp-1.war","foo.war");

        // Should not throw an Exception
        jetty.load();

        // Start it
        jetty.start();
    }

    @AfterClass
    public static void teardownEnvironment() throws Exception
    {
        // Stop jetty.
        jetty.stop();
    }

    @Test
    public void testStartupContext()
    {
        // Check Server for Handlers
        jetty.assertWebAppContextsExists("/foo");

        File workDir = jetty.getJettyDir("workish");

        System.err.println("workDir="+workDir);
        
        // Test for regressions
        assertDirNotExists("root of work directory",workDir,"webinf");
        assertDirNotExists("root of work directory",workDir,"jsp");

        // Test for correct behaviour
        Assert.assertTrue("Should have generated directory in work directory: " + workDir,hasJettyGeneratedPath(workDir,"foo.war"));
    }

    private static boolean hasJettyGeneratedPath(File basedir, String expectedWarFilename)
    {
        for (File path : basedir.listFiles())
        {
            if (path.exists() && path.isDirectory() && path.getName().startsWith("jetty-") && path.getName().contains(expectedWarFilename))
            {
                System.out.println("Found expected generated directory: " + path);
                return true;
            }
        }

        System.err.println("did not find "+expectedWarFilename+" in "+Arrays.asList(basedir.listFiles()));
        return false;
    }

    public static void assertDirNotExists(String msg, File workDir, String subdir)
    {
        File dir = new File(workDir,subdir);
        Assert.assertFalse("Should not have " + subdir + " in " + msg + " - " + workDir,dir.exists());
    }
}
