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

package org.eclipse.jetty.ee10.annotations;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.ee10.webapp.WebDescriptor;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(WorkDirExtension.class)
public class TestRunAsAnnotation
{
    public WorkDir workDir;

    @Test
    public void testRunAsAnnotation() throws Exception
    {
        Path dummyXml = workDir.getEmptyPathDir().resolve("dummy.xml");
        Files.createFile(dummyXml);
        Resource dummyXmlResource = ResourceFactory.root().newResource(dummyXml);

        WebAppContext wac = new WebAppContext();
        
        //pre-add a servlet but not by descriptor
        ServletHolder holder = new ServletHolder();
        holder.setName("foo1");
        holder.setHeldClass(ServletC.class);
        holder.setInitOrder(1); //load on startup
        wac.getServletHandler().addServletWithMapping(holder, "/foo/*");
        
        //add another servlet of the same class, but as if by descriptor
        ServletHolder holder2 = new ServletHolder();
        holder2.setName("foo2");
        holder2.setHeldClass(ServletC.class);
        holder2.setInitOrder(1);
        wac.getServletHandler().addServletWithMapping(holder2, "/foo2/*");
        wac.getMetaData().setOrigin(holder2.getName() + ".servlet.run-as", new WebDescriptor(dummyXmlResource));
        
        AnnotationIntrospector parser = new AnnotationIntrospector(wac);
        RunAsAnnotationHandler handler = new RunAsAnnotationHandler(wac);
        parser.registerHandler(handler);
        parser.introspect(new ServletC(), null);
        
        assertEquals("admin", holder.getRunAsRole());
        assertEquals(null, holder2.getRunAsRole());
    }
}
