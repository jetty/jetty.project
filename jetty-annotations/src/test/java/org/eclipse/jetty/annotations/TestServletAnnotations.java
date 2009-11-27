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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.annotations.AnnotationParser.DiscoverableAnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.annotations.AnnotationParser.ListValue;
import org.eclipse.jetty.annotations.AnnotationParser.SimpleValue;
import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.plus.annotation.RunAsCollection;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.webapp.WebAppContext;


import junit.framework.TestCase;

/**
 * TestServletAnnotations
 *
 *
 */
public class TestServletAnnotations extends TestCase
{


    public void testServletAnnotation()
    throws Exception
    {
        List<String> classes = new ArrayList<String>();
        classes.add("org.eclipse.jetty.annotations.ServletC");
        AnnotationParser parser = new AnnotationParser();

        WebAppContext wac = new WebAppContext();
        LifeCycleCallbackCollection collection = new LifeCycleCallbackCollection();
        wac.setAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION, collection);
        RunAsCollection runAsCollection = new RunAsCollection();
        wac.setAttribute(RunAsCollection.RUNAS_COLLECTION, runAsCollection);
        parser.registerAnnotationHandler("javax.servlet.annotation.WebServlet", new WebServletAnnotationHandler(wac));
       
        parser.parse(classes, new ClassNameResolver () 
        {
            public boolean isExcluded(String name)
            {
                return false;
            }

            public boolean shouldOverride(String name)
            {
                return false;
            }

        });
       
        ServletHolder[] holders = wac.getServletHandler().getServlets();
        assertNotNull(holders);
        assertEquals(1, holders.length);
        assertEquals("CServlet", holders[0].getName());
        ServletMapping[] mappings = wac.getServletHandler().getServletMappings();
        assertNotNull(mappings);
        assertEquals(1, mappings.length);
        String[] paths = mappings[0].getPathSpecs();
        assertNotNull(paths);
        assertEquals(2, paths.length);
    }
}
