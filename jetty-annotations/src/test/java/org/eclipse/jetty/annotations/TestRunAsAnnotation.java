//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestRunAsAnnotation
{
    @Test
    public void testRunAsAnnotation() throws Exception
    {
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
        wac.getMetaData().setOrigin(holder2.getName() + ".servlet.run-as", new WebDescriptor(null));
        
        AnnotationIntrospector parser = new AnnotationIntrospector();
        RunAsAnnotationHandler handler = new RunAsAnnotationHandler(wac);
        parser.registerHandler(handler);
        parser.introspect(ServletC.class);
        
        assertEquals("admin", holder.getRunAsRole());
        assertEquals(null, holder2.getRunAsRole());

        
    }
}
