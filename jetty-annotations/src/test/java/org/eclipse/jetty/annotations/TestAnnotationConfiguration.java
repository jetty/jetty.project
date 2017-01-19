//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
