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
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import junit.framework.TestCase;

import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.FragmentDescriptor;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * TestAnnotationConfiguration
 *
 *
 */
public class TestAnnotationConfiguration extends TestCase
{
    public void testGetFragmentFromJar ()
    throws Exception
    {
        String dir = System.getProperty("basedir", ".");   
        File file = new File(dir);
        file=new File(file.getCanonicalPath());
        URL url=file.toURL();

        Resource jar1 = Resource.newResource(url+"file.jar");

        AbstractConfiguration config = new AbstractConfiguration()
        {

            public void configure(WebAppContext context) throws Exception
            {
                // TODO Auto-generated method stub
                
            }

            public void deconfigure(WebAppContext context) throws Exception
            {
                // TODO Auto-generated method stub
                
            }

            public void postConfigure(WebAppContext context) throws Exception
            {
                // TODO Auto-generated method stub
                
            }

            public void preConfigure(WebAppContext context) throws Exception
            {
                // TODO Auto-generated method stub
                
            }
            
        };
        WebAppContext wac = new WebAppContext();

        List<FragmentDescriptor> frags = new ArrayList<FragmentDescriptor>();
        frags.add(new FragmentDescriptor(Resource.newResource("jar:"+url+"file.jar!/fooa.props")));
        frags.add(new FragmentDescriptor(Resource.newResource("jar:"+url+"file2.jar!/foob.props")));

        assertNotNull(config.getFragmentFromJar(jar1, frags));
    }
}
