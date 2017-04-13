//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.annotations;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.FragmentDescriptor;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.Test;

/**
 * TestAnnotationConfiguration
 *
 *
 */
public class TestAnnotationConfiguration
{
    
    public class TestableAnnotationConfiguration extends AnnotationConfiguration
    {
        public void assertAnnotationDiscovery (boolean b)
        {
            
            if (!b)
                assertTrue(_discoverableAnnotationHandlers.isEmpty());
            else
                assertFalse(_discoverableAnnotationHandlers.isEmpty());
        }
    }
    
    
    @Test
    public void testAnnotationScanControl() throws Exception
    { 
        File web25 = MavenTestingUtils.getTestResourceFile("web25.xml");
        File web31 = MavenTestingUtils.getTestResourceFile("web31.xml");
        File web31false = MavenTestingUtils.getTestResourceFile("web31false.xml");
        
        
        //check that a 2.5 webapp won't discover annotations
        TestableAnnotationConfiguration config25 = new TestableAnnotationConfiguration();
        WebAppContext context25 = new WebAppContext();
        context25.setAttribute(AnnotationConfiguration.MULTI_THREADED, Boolean.FALSE);
        context25.setAttribute(AnnotationConfiguration.MAX_SCAN_WAIT, new Integer(0));
        context25.getMetaData().setWebXml(Resource.newResource(web25));
        context25.getServletContext().setEffectiveMajorVersion(2);
        context25.getServletContext().setEffectiveMinorVersion(5);
        config25.configure(context25);
        config25.assertAnnotationDiscovery(false);
        
        //check that a 2.5 webapp with configurationDiscovered will discover annotations
        TestableAnnotationConfiguration config25b = new TestableAnnotationConfiguration();
        WebAppContext context25b = new WebAppContext();
        context25b.setAttribute(AnnotationConfiguration.MULTI_THREADED, Boolean.FALSE);
        context25b.setAttribute(AnnotationConfiguration.MAX_SCAN_WAIT, new Integer(0));        
        context25b.setConfigurationDiscovered(true);
        context25b.getMetaData().setWebXml(Resource.newResource(web25));
        context25b.getServletContext().setEffectiveMajorVersion(2);
        context25b.getServletContext().setEffectiveMinorVersion(5);
        config25b.configure(context25b);
        config25b.assertAnnotationDiscovery(true);
        
        //check that a 3.x webapp with metadata true won't discover annotations
        TestableAnnotationConfiguration config31 = new TestableAnnotationConfiguration();
        WebAppContext context31 = new WebAppContext();
        context31.setAttribute(AnnotationConfiguration.MULTI_THREADED, Boolean.FALSE);
        context31.setAttribute(AnnotationConfiguration.MAX_SCAN_WAIT, new Integer(0));        
        context31.getMetaData().setWebXml(Resource.newResource(web31));
        context31.getServletContext().setEffectiveMajorVersion(3);
        context31.getServletContext().setEffectiveMinorVersion(1);
        config31.configure(context31);
        config31.assertAnnotationDiscovery(false);
        
        //check that a 3.x webapp with metadata false will discover annotations
        TestableAnnotationConfiguration config31b = new TestableAnnotationConfiguration();
        WebAppContext context31b = new WebAppContext();
        context31b.setAttribute(AnnotationConfiguration.MULTI_THREADED, Boolean.FALSE);
        context31b.setAttribute(AnnotationConfiguration.MAX_SCAN_WAIT, new Integer(0));        
        context31b.getMetaData().setWebXml(Resource.newResource(web31false));
        context31b.getServletContext().setEffectiveMajorVersion(3);
        context31b.getServletContext().setEffectiveMinorVersion(1);
        config31b.configure(context31b);
        config31b.assertAnnotationDiscovery(true);
    }
    
    
    
    @Test
    public void testGetFragmentFromJar() throws Exception
    {
        String dir = MavenTestingUtils.getTargetTestingDir("getFragmentFromJar").getAbsolutePath();
        File file = new File(dir);
        file=new File(file.getCanonicalPath());
        URL url=file.toURL();

        Resource jar1 = Resource.newResource(url+"file.jar");

        AnnotationConfiguration config = new AnnotationConfiguration();
        WebAppContext wac = new WebAppContext();

        List<FragmentDescriptor> frags = new ArrayList<FragmentDescriptor>();
        frags.add(new FragmentDescriptor(Resource.newResource("jar:"+url+"file.jar!/fooa.props")));
        frags.add(new FragmentDescriptor(Resource.newResource("jar:"+url+"file2.jar!/foob.props")));

        assertNotNull(config.getFragmentFromJar(jar1, frags));
    }
}
