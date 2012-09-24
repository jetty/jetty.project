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

package org.eclipse.jetty.osgi.boot;

import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.options;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import junit.framework.Assert;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Inject;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.container.def.PaxRunnerOptions;
import org.ops4j.pax.exam.junit.Configuration;
import org.ops4j.pax.exam.junit.JUnit4TestRunner;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

/**
 * Pax-Exam to make sure the jetty-osgi-boot can be started along with the httpservice web-bundle.
 * Then make sure we can deploy an OSGi service on the top of this.
 */
@RunWith( JUnit4TestRunner.class )
public class TestJettyOSGiBootWithJsp
{
    private static final boolean LOGGING_ENABLED = true;
    private static final boolean REMOTE_DEBUGGING = false;

    @Inject
    BundleContext bundleContext = null;

    @Configuration
    public static Option[] configure()
    {
    	File etcFolder = new File("src/test/config/etc");
    	String etc = "file://" + etcFolder.getAbsolutePath();

    	ArrayList<Option> options = new ArrayList<Option>();
    	options.add(PaxRunnerOptions.vmOption("-Djetty.port=9876 -Djetty.home="+ etcFolder.getParentFile().getAbsolutePath() +
    			" -D" + OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS +
                "="+etc+"/jetty.xml;"+etc+"/jetty-selector.xml;"+etc+"/jetty-deployer.xml;" + etc + "/jetty-testrealm.xml"));
    	options.add(CoreOptions.equinox());
    	options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*",
    			"org.w3c.*", "javax.xml.*"));
    	options.addAll(TestJettyOSGiBootCore.coreJettyDependencies());

    	// Enable Logging
    	if(LOGGING_ENABLED) {
    	    options.addAll(Arrays.asList(options(
                // install log service using pax runners profile abstraction (there are more profiles, like DS)
        	// logProfile(),
        	// this is how you set the default log level when using pax logging (logProfile)
        	systemProperty( "org.ops4j.pax.logging.DefaultServiceLog.level" ).value( "INFO" )
    	    )));
    	}
    	
        options.addAll(jspDependencies());

    	// Remote JDWP Debugging
    	if(REMOTE_DEBUGGING) {
    	    options.addAll(Arrays.asList(options(
    	        // this just adds all what you write here to java vm argumenents of the (new) osgi process.
    	        PaxRunnerOptions.vmOption( "-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5006" )
    	    )));
    	}

    	return options.toArray(new Option[options.size()]);
    }
    
    public static List<Option> jspDependencies() {
    	List<Option> res = new ArrayList<Option>();
    	/* orbit deps */
    	res.add(mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "javax.servlet.jsp" ).versionAsInProject());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "javax.servlet.jsp.jstl" ).versionAsInProject());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "javax.el" ).versionAsInProject());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "com.sun.el" ).versionAsInProject());
	    res.add(mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "org.apache.jasper.glassfish" ).versionAsInProject());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "org.apache.taglibs.standard.glassfish" ).versionAsInProject());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "org.eclipse.jdt.core" ).versionAsInProject());

	    /* jetty-osgi deps */
        res.add(mavenBundle().groupId( "org.eclipse.jetty.osgi" ).artifactId( "jetty-osgi-boot-jsp" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.osgi" ).artifactId( "jetty-osgi-boot" ).versionAsInProject().start());

        res.add(mavenBundle().groupId( "org.eclipse.jetty" ).artifactId( "test-jetty-webapp" ).classifier("webbundle").versionAsInProject());

        res.add(mavenBundle().groupId( "org.eclipse.equinox.http" ).artifactId( "servlet" ).versionAsInProject().start());
        return res;
    }

    /**
     * You will get a list of bundles installed by default
     * plus your testcase, wrapped into a bundle called pax-exam-probe
     */
    @Test
    public void listBundles() throws Exception
    {
    	Map<String,Bundle> bundlesIndexedBySymbolicName = new HashMap<String, Bundle>();
        for( Bundle b : bundleContext.getBundles() )
        {
        	bundlesIndexedBySymbolicName.put(b.getSymbolicName(), b);
        }
        Bundle websocketServer = bundlesIndexedBySymbolicName.get("org.eclipse.jetty.websocket.server");
        Assert.assertNotNull("Could not find the org.eclipse.jetty.websocket.server bundle", websocketServer);
        Assert.assertEquals(Bundle.RESOLVED, websocketServer.getState());

        Bundle osgiBoot = bundlesIndexedBySymbolicName.get("org.eclipse.jetty.osgi.boot");
        Assert.assertNotNull("Could not find the org.eclipse.jetty.osgi.boot bundle", osgiBoot);
        Assert.assertEquals(Bundle.ACTIVE, osgiBoot.getState());

        Bundle osgiBootJsp = bundlesIndexedBySymbolicName.get("org.eclipse.jetty.osgi.boot.jsp");
        Assert.assertNotNull("Could not find the org.eclipse.jetty.osgi.boot.jsp bundle", osgiBootJsp);
        Assert.assertEquals("The fragment jsp is not correctly resolved.",
        		Bundle.RESOLVED, osgiBootJsp.getState());

        Bundle testWebBundle = bundlesIndexedBySymbolicName.get("org.eclipse.jetty.test-jetty-webapp");
        Assert.assertNotNull("Could not find the org.eclipse.jetty.test-jetty-webappp bundle", osgiBootJsp);
        Assert.assertEquals("The test-jetty-webapp is not correctly resolved", Bundle.ACTIVE, testWebBundle.getState());

        //now test the jsp/dump.jsp
        HttpClient client = new HttpClient();
        try
        {
            client.start();
            Response response = client.GET("http://127.0.0.1:9876/jsp/dump.jsp").get(5, TimeUnit.SECONDS);
            Assert.assertEquals(HttpStatus.OK_200, response.status());

            String content = new String(((HttpContentResponse)response).content());
            //System.err.println("content: " + content);
            Assert.assertTrue(content.indexOf("<tr><th>ServletPath:</th><td>/jsp/dump.jsp</td></tr>") != -1);
        }
        finally
        {
            client.stop();
        }

    }


}
