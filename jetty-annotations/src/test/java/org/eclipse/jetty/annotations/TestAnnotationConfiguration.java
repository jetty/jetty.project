// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.annotations;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.List;

import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.Fragment;
import org.eclipse.jetty.webapp.WebAppContext;

import junit.framework.TestCase;

/**
 * TestAnnotationConfiguration
 *
 *
 */
public class TestAnnotationConfiguration extends TestCase
{

    /**
     * Test method for {@link org.eclipse.jetty.annotations.AbstractConfiguration#parseWebInfLib(org.eclipse.jetty.webapp.WebAppContext, org.eclipse.jetty.annotations.AnnotationParser)}.
     */
    public void testExclusions()
    throws Exception
    {
        AnnotationConfiguration config = new AnnotationConfiguration();
        WebAppContext wac = new WebAppContext();
        
        Resource r = Resource.newResource("file:///home/janb/file.jar");
        List<String> orderedJars = new ArrayList<String>();
        
        //empty ordering excludes all jars
        assertTrue(config.isExcluded(r, orderedJars));
        
        //an ordering with name included
        orderedJars.add("file.jar");
        orderedJars.add("abc.jar");
        orderedJars.add("xyz.jar");
        assertFalse(config.isExcluded(r, orderedJars));
        
        //an ordering with name excluded
        orderedJars.remove("file.jar");
        assertTrue(config.isExcluded(r, orderedJars));
    }

    
    public void testGetFragmentFromJar ()
    throws Exception
    {
        String dir = System.getProperty("basedir", ".");   
        File file = new File(dir);
        file=new File(file.getCanonicalPath());
        URL url=file.toURL();

        Resource jar1 = Resource.newResource(url+"file.jar");

        AnnotationConfiguration config = new AnnotationConfiguration();
        WebAppContext wac = new WebAppContext();

        List<Fragment> frags = new ArrayList<Fragment>();
        frags.add(new Fragment(Resource.newResource("jar:"+url+"file.jar!/fooa.props"), null));
        frags.add(new Fragment(Resource.newResource("jar:"+url+"file2.jar!/foob.props"), null));

        assertNotNull(config.getFragmentFromJar(jar1, frags));
    }
}
