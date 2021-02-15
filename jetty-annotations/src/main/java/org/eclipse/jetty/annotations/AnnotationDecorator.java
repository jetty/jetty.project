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

import org.eclipse.jetty.util.Decorator;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * AnnotationDecorator
 */
public class AnnotationDecorator implements Decorator
{
    protected AnnotationIntrospector _introspector = new AnnotationIntrospector();

    public AnnotationDecorator(WebAppContext context)
    {
        registerHandlers(context);
    }

    public void registerHandlers(WebAppContext context)
    {
        _introspector.registerHandler(new ResourceAnnotationHandler(context));
        _introspector.registerHandler(new ResourcesAnnotationHandler(context));
        _introspector.registerHandler(new RunAsAnnotationHandler(context));
        _introspector.registerHandler(new PostConstructAnnotationHandler(context));
        _introspector.registerHandler(new PreDestroyAnnotationHandler(context));
        _introspector.registerHandler(new DeclareRolesAnnotationHandler(context));
        _introspector.registerHandler(new MultiPartConfigAnnotationHandler(context));
        _introspector.registerHandler(new ServletSecurityAnnotationHandler(context));
    }

    /**
     * Look for annotations that can be discovered with introspection:
     * <ul>
     * <li> Resource </li>
     * <li> Resources </li>
     * <li> PostConstruct </li>
     * <li> PreDestroy </li>
     * <li> ServletSecurity? </li>
     * </ul>
     *
     * @param o the object ot introspect
     */
    protected void introspect(Object o)
    {
        _introspector.introspect(o.getClass());
    }

    @Override
    public Object decorate(Object o)
    {
        introspect(o);
        return o;
    }

    @Override
    public void destroy(Object o)
    {

    }
}
