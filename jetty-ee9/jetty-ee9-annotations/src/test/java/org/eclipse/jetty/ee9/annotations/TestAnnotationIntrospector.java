//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.annotations;

import java.io.File;

import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.ee9.servlet.Source;
import org.eclipse.jetty.ee9.webapp.FragmentDescriptor;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.ee9.webapp.WebDescriptor;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestAnnotationIntrospector
{
    @Test
    public void testIsIntrospectable() throws Exception
    {
        try (StacklessLogging ignore = new StacklessLogging(AnnotationIntrospector.class))
        {
            WebAppContext wac = new WebAppContext();
            AnnotationIntrospector introspector = new AnnotationIntrospector(wac);
            //can't introspect nothing
            assertFalse(introspector.isIntrospectable(null, null));

            //can introspect if no metadata to say otherwise
            assertTrue(introspector.isIntrospectable(new Object(), null));

            //can introspect if metdata isn't a BaseHolder
            assertTrue(introspector.isIntrospectable(new Object(), new Object()));

            //an EMBEDDED sourced servlet can be introspected
            ServletHolder holder = new ServletHolder();
            holder.setHeldClass(ServletE.class);
            assertTrue(introspector.isIntrospectable(new ServletE(), holder));

            //a JAVAX API sourced servlet can be introspected
            holder = new ServletHolder(Source.JAVAX_API);
            holder.setHeldClass(ServletE.class);
            assertTrue(introspector.isIntrospectable(new ServletE(), holder));

            //an ANNOTATION sourced servlet can be introspected
            holder = new ServletHolder(new Source(Source.Origin.ANNOTATION, ServletE.class.getName()));
            holder.setHeldClass(ServletE.class);
            assertTrue(introspector.isIntrospectable(new ServletE(), holder));

            //a DESCRIPTOR sourced servlet can be introspected if web.xml metdata-complete==false
            File file = MavenTestingUtils.getTestResourceFile("web31false.xml");
            Resource resource = Resource.newResource(file);
            wac.getMetaData().setWebDescriptor(new WebDescriptor(resource));
            holder = new ServletHolder(new Source(Source.Origin.DESCRIPTOR, resource.toString()));
            assertTrue(introspector.isIntrospectable(new ServletE(), holder));

            //a DESCRIPTOR sourced servlet can be introspected if web-fragment.xml medata-complete==false && web.xml metadata-complete==false
            file = MavenTestingUtils.getTestResourceFile("web-fragment4false.xml");
            resource = Resource.newResource(file);
            wac.getMetaData().addFragmentDescriptor(Resource.newResource(file.getParentFile()), new FragmentDescriptor(resource));
            holder = new ServletHolder(new Source(Source.Origin.DESCRIPTOR, resource.toString()));
            assertTrue(introspector.isIntrospectable(new ServletE(), holder));

            //a DESCRIPTOR sourced servlet cannot be introspected if web-fragment.xml medata-complete==true (&& web.xml metadata-complete==false)
            file = MavenTestingUtils.getTestResourceFile("web-fragment4true.xml");
            resource = Resource.newResource(file);
            wac.getMetaData().addFragmentDescriptor(Resource.newResource(file.getParentFile()), new FragmentDescriptor(resource));
            holder = new ServletHolder(new Source(Source.Origin.DESCRIPTOR, resource.toString()));
            assertFalse(introspector.isIntrospectable(new ServletE(), holder));

            //a DESCRIPTOR sourced servlet cannot be introspected if web.xml medata-complete==true
            file = MavenTestingUtils.getTestResourceFile("web31true.xml");
            resource = Resource.newResource(file);
            wac.getMetaData().setWebDescriptor(new WebDescriptor(resource));
            holder = new ServletHolder(new Source(Source.Origin.DESCRIPTOR, resource.toString()));
            assertFalse(introspector.isIntrospectable(new ServletE(), holder));
        }
    }
}
