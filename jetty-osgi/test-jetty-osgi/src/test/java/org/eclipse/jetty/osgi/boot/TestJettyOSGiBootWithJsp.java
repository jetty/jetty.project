// ========================================================================
// Copyright (c) 2010 Intalio, Inc.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// Contributors:
//    Hugues Malphettes - initial API and implementation
// ========================================================================
package org.eclipse.jetty.osgi.boot;

import static org.ops4j.pax.exam.CoreOptions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import junit.framework.Assert;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
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
    private static final boolean LOGGING_ENABLED = false;
    private static final boolean REMOTE_DEBUGGING = false;
    
    @Inject
    BundleContext bundleContext = null;

    @Configuration
    public static Option[] configure()
    {
    	File testrealm = new File("src/test/config/etc/jetty-testrealm.xml");
    	
    	ArrayList<Option> options = new ArrayList<Option>();
    	options.addAll(TestJettyOSGiBootCore.provisionCoreJetty());
    	
    	// Enable Logging
    	if(LOGGING_ENABLED) {
    	    options.addAll(Arrays.asList(options(
                // install log service using pax runners profile abstraction (there are more profiles, like DS)
        	// logProfile(),
        	// this is how you set the default log level when using pax logging (logProfile)
        	systemProperty( "org.ops4j.pax.logging.DefaultServiceLog.level" ).value( "INFO" )
    	    )));
    	}
    	
    	// Remote JDWP Debugging
    	if(REMOTE_DEBUGGING) {
    	    options.addAll(Arrays.asList(options(
    	        // this just adds all what you write here to java vm argumenents of the (new) osgi process.
    	        PaxRunnerOptions.vmOption( "-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5006" )
    	    )));
    	}

    	// Standard Options
    	options.addAll(Arrays.asList(options(
            PaxRunnerOptions.vmOption("-Djetty.port=9876 -D" + OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS + 
                "=etc/jetty.xml;" + testrealm.getAbsolutePath()),

            /* orbit deps */
            mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "javax.servlet.jsp" ).versionAsInProject(),
            mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "javax.servlet.jsp.jstl" ).versionAsInProject(),
            mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "javax.el" ).versionAsInProject(),
            mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "com.sun.el" ).versionAsInProject(),
    	    mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "org.apache.jasper.glassfish" ).versionAsInProject(),
            mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "org.apache.taglibs.standard.glassfish" ).versionAsInProject(),
            mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "org.eclipse.jdt.core" ).versionAsInProject(),
    	    
    	    /* jetty-osgi deps */
    	    mavenBundle().groupId( "org.eclipse.jetty.osgi" ).artifactId( "jetty-osgi-boot" ).versionAsInProject().start(),
            mavenBundle().groupId( "org.eclipse.jetty.osgi" ).artifactId( "jetty-osgi-boot-jsp" ).versionAsInProject().start(),

            mavenBundle().groupId( "org.eclipse.jetty" ).artifactId( "test-jetty-webapp" ).classifier("webbundle").versionAsInProject()
            
            // mavenBundle().groupId( "org.eclipse.equinox.http" ).artifactId( "servlet" ).versionAsInProject().start()
        )));
    	
    	return options.toArray(new Option[options.size()]);
    }

    /**
     * You will get a list of bundles installed by default
     * plus your testcase, wrapped into a bundle called pax-exam-probe
     */
    @Test
    @Ignore
    public void listBundles() throws Exception
    {
    	Map<String,Bundle> bundlesIndexedBySymbolicName = new HashMap<String, Bundle>();
        for( Bundle b : bundleContext.getBundles() )
        {
        	bundlesIndexedBySymbolicName.put(b.getSymbolicName(), b);
        	System.err.println("Got " + b.getSymbolicName() + " " + b.getVersion().toString() + " " + b.getState());
        }
        
        Bundle osgiBoot = bundlesIndexedBySymbolicName.get("org.eclipse.jetty.osgi.boot");
        Assert.assertNotNull("Could not find the org.eclipse.jetty.osgi.boot bundle", osgiBoot);
        Assert.assertTrue(osgiBoot.getState() == Bundle.ACTIVE);
        
        Bundle osgiBootJsp = bundlesIndexedBySymbolicName.get("org.eclipse.jetty.osgi.boot.jsp");
        Assert.assertNotNull("Could not find the org.eclipse.jetty.osgi.boot.jsp bundle", osgiBootJsp);
        Assert.assertTrue("The fragment jsp is not correctly resolved " + osgiBootJsp.getState(), osgiBootJsp.getState() == Bundle.RESOLVED);
        
        Bundle testWebBundle = bundlesIndexedBySymbolicName.get("org.eclipse.jetty.test-jetty-webapp");
        Assert.assertNotNull("Could not find the org.eclipse.jetty.test-jetty-webapp bundle", osgiBootJsp);
        Assert.assertTrue("The bundle org.eclipse.jetty.test-jetty-webapp is not correctly resolved", testWebBundle.getState() == Bundle.ACTIVE);
        
        //now test the jsp/dump.jsp
        HttpClient client = new HttpClient();
        client.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        try
        {
            client.start();
            
            ContentExchange getExchange = new ContentExchange();
            getExchange.setURL("http://127.0.0.1:9876/jsp/dump.jsp");
            getExchange.setMethod(HttpMethods.GET);
     
            client.send(getExchange);
            int state = getExchange.waitForDone();
            Assert.assertEquals("state should be done", HttpExchange.STATUS_COMPLETED, state);
            
            String content = null;
            int responseStatus = getExchange.getResponseStatus();
            Assert.assertEquals(HttpStatus.OK_200, responseStatus);
            if (responseStatus == HttpStatus.OK_200) {
                content = getExchange.getResponseContent();
            }
            //System.err.println("content: " + content);
            Assert.assertTrue(content.indexOf("<tr><th>ServletPath:</th><td>/jsp/dump.jsp</td></tr>") != -1);
        }
        finally
        {
            client.stop();
        }
        
    }

	
}
