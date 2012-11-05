//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package com.acme;

import java.util.Set;
import java.util.ArrayList;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletContext;
import javax.servlet.annotation.HandlesTypes;
import javax.servlet.ServletContainerInitializer;

@HandlesTypes ({javax.servlet.Servlet.class, Foo.class})
public class FooInitializer implements ServletContainerInitializer
{

    public void onStartup(Set<Class<?>> classes, ServletContext context)
    {
        context.setAttribute("com.acme.Foo", new ArrayList<Class>(classes));
        ServletRegistration.Dynamic reg = context.addServlet("AnnotationTest", "com.acme.AnnotationTest");
        context.setAttribute("com.acme.AnnotationTest.complete", (reg == null));
    }
}
