//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
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
import org.osgi.framework.ServiceReference;

/**
 * TestJettyOSGiBootWebAppAsService
 * 
 * Tests deployment of a WebAppContext as an osgi Service.
 * 
 * Tests the ServiceWebAppProvider.
 * 
 * Pax-Exam to make sure the jetty-osgi-boot can be started along with the httpservice web-bundle.
 * Then make sure we can deploy an OSGi service on the top of this.
 */
@RunWith( JUnit4TestRunner.class )
public class TestJettyOSGiBootWebAppAsService extends AbstractTestOSGi
{
    private static final boolean LOGGING_ENABLED = false;
    private static final boolean REMOTE_DEBUGGING = false;
    
    @Inject
    BundleContext bundleContext = null;

    @Configuration
    public static Option[] configure()
    {
    	ArrayList<Option> options = new ArrayList<Option>();
        addMoreOSGiContainers(options);

        options.add(CoreOptions.junitBundles());
        optinos.addAll(configureJettyHomeAndPort("jetty-selector.xml"));
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
        return options.toArray(new Option[options.size()]);

    }
    
    public static List<Option> configureJettyHomeAndPort(String jettySelectorFileName)
    {
        File etcFolder = new File("src/test/config/etc");
        String etc = "file://" + etcFolder.getAbsolutePath();
        List<Option> options = new ArrayList<Option>();
        options.add(systemProperty(OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS)
                .value(etc + "/jetty.xml;" +
                       etc + "/" + jettySelectorFileName + ";" +
                       etc + "/jetty-deployer.xml;" +
                       etc + "/jetty-testrealm.xml"));
        options.add(systemProperty("jetty.port").value(String.valueOf(TestJettyOSGiBootCore.DEFAULT_JETTY_HTTP_PORT)));
        options.add(systemProperty("jetty.home").value(etcFolder.getParentFile().getAbsolutePath()));
        return options;
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


            //a bundle that registers a webapp as a service for the jetty osgi core to pick up and deploy
        res.add(mavenBundle().groupId( "org.eclipse.jetty.osgi" ).artifactId( "test-jetty-osgi-webapp" ).versionAsInProject().start());
        return res;	
    }



    @Test
    public void assertAllBundlesActiveOrResolved()
    {
        assertAllBundlesActiveOrResolved(bundleContext);
    }

    /**
     */
    @Test
    public void testBundle() throws Exception
    {
        
        //now test the jsp/dump.jsp
        HttpClient client = new HttpClient();
        try
        {
            client.start();
            
            ContentResponse response = client.GET("http://127.0.0.1:"+
                    TestJettyOSGiBootCore.DEFAULT_JETTY_HTTP_PORT+"/acme/index.html");
            Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

            String content = new String(response.getContent());
            Assert.assertTrue(content.indexOf("<h1>Test OSGi WebApp</h1>") != -1);
            }
        }
        finally
        {
            client.stop();
        }
        
        ServiceReference[] refs = bundleContext.getServiceReferences(ContextHandler.class.getName(), null);
        Assert.assertNotNull(refs);
        Assert.assertEquals(1,refs.length);
        WebAppContext wac = (WebAppContext)bundleContext.getService(refs[0]);
        Assert.assertEquals("/acme", wac.getContextPath());
    }

	
}
