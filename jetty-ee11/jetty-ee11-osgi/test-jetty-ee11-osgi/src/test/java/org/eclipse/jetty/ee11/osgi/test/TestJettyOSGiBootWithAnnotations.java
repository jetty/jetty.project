//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee11.osgi.test;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.MultiPartRequestContent;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MultiPart;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.osgi.framework.BundleContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;

/**
 * Pax-Exam to make sure the jetty-ee11-osgi-boot can be started along with the
 * httpservice web-bundle. Then make sure we can deploy an OSGi service on the
 * top of this.
 */
@RunWith(PaxExam.class)
public class TestJettyOSGiBootWithAnnotations
{
    @Inject
    BundleContext bundleContext = null;

    @Configuration
    public static Option[] configure()
    {
        ArrayList<Option> options = new ArrayList<>();

        options.addAll(TestOSGiUtil.configurePaxExamLogging());

        options.add(TestOSGiUtil.optionalRemoteDebug());
        options.add(CoreOptions.junitBundles());
        options.addAll(TestOSGiUtil.configureJettyHomeAndPort(false, "jetty-http-boot-with-annotations.xml"));
        options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*", "org.w3c.*", "javax.sql.*", "javax.xml.*"));
        options.add(CoreOptions.systemPackages("com.sun.org.apache.xalan.internal.res", "com.sun.org.apache.xml.internal.utils",
            "com.sun.org.apache.xml.internal.utils", "com.sun.org.apache.xpath.internal",
            "com.sun.org.apache.xpath.internal.jaxp", "com.sun.org.apache.xpath.internal.objects"));

        TestOSGiUtil.coreJettyDependencies(options);
        TestOSGiUtil.coreJspDependencies(options);
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-java-client").versionAsInProject().start());
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-client").versionAsInProject().start());

        options.addAll(annotationDependencies());
        options.add(mavenBundle().groupId("org.eclipse.jetty.ee11.osgi").artifactId("test-jetty-ee11-osgi-fragment").versionAsInProject().noStart());
        return options.toArray(new Option[0]);
    }

    public static List<Option> annotationDependencies()
    {
        List<Option> res = new ArrayList<>();
        res.add(mavenBundle().groupId("org.eclipse.jetty.demos").artifactId("jetty-servlet5-demo-container-initializer").versionAsInProject());
        res.add(mavenBundle().groupId("org.eclipse.jetty.demos").artifactId("jetty-servlet5-demo-mock-resources").versionAsInProject());
        //test webapp bundle
        res.add(mavenBundle().groupId("org.eclipse.jetty.demos").artifactId("jetty-servlet5-demo-spec-webapp").classifier("webbundle-ee11").versionAsInProject());
        return res;
    }

    @Test
    public void testIndex() throws Exception
    {
        if (Boolean.getBoolean(TestOSGiUtil.BUNDLE_DEBUG))
            TestOSGiUtil.diagnoseBundles(bundleContext);

        HttpClient client = new HttpClient();
        try
        {
            client.start();
            String port = System.getProperty("boot.annotations.port");
            assertNotNull(port);

            ContentResponse response = client.GET("http://127.0.0.1:" + port + "/ee11-demo-spec/index.html");
            assertEquals("Response status code", HttpStatus.OK_200, response.getStatus());

            String content = response.getContentAsString();
            TestOSGiUtil.assertContains("Response contents", content, "Demo Specification WebApp");

            Request req = client.POST("http://127.0.0.1:" + port + "/ee11-demo-spec/test");
            response = req.send();
            assertEquals("Response status code", HttpStatus.OK_200, response.getStatus());
            content = response.getContentAsString();
            TestOSGiUtil.assertContains("Response contents", content,
                "<p><b>Result: <span class=\"pass\">PASS</span></p>");

            response = client.GET("http://127.0.0.1:" + port + "/ee11-demo-spec/frag.html");
            assertEquals("Response status code", HttpStatus.OK_200, response.getStatus());
            content = response.getContentAsString();
            TestOSGiUtil.assertContains("Response contents", content, "<h1>FRAGMENT</h1>");
            MultiPartRequestContent multiPart = new MultiPartRequestContent();
            multiPart.addPart(new MultiPart.ContentSourcePart("field", null, HttpFields.EMPTY, new StringRequestContent("foo")));
            multiPart.close();

            response = client.newRequest("http://127.0.0.1:" + port + "/ee11-demo-spec/multi").method("POST")
                .body(multiPart).send();
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
        finally
        {
            client.stop();
        }
    }
}
