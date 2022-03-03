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

import java.util.Objects;

import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.Decorator;

/**
 * AnnotationDecorator
 */
public class AnnotationDecorator implements Decorator
{
    protected AnnotationIntrospector _introspector;
    protected WebAppContext _context;

    public AnnotationDecorator(WebAppContext context)
    {
        _context = Objects.requireNonNull(context);
        _introspector = new AnnotationIntrospector(_context);
        registerHandlers();
    }

    private void registerHandlers()
    {
        _introspector.registerHandler(new ResourceAnnotationHandler(_context));
        _introspector.registerHandler(new ResourcesAnnotationHandler(_context));
        _introspector.registerHandler(new RunAsAnnotationHandler(_context));
        _introspector.registerHandler(new PostConstructAnnotationHandler(_context));
        _introspector.registerHandler(new PreDestroyAnnotationHandler(_context));
        _introspector.registerHandler(new DeclareRolesAnnotationHandler(_context));
        _introspector.registerHandler(new MultiPartConfigAnnotationHandler(_context));
        _introspector.registerHandler(new ServletSecurityAnnotationHandler(_context));
    }

    /**
     * Look for annotations that can be discovered with introspection:
     * <ul>
     * <li> Resource </li>
     * <li> Resources </li>
     * <li> RunAs </li>
     * <li> PostConstruct </li>
     * <li> PreDestroy </li>
     * <li> DeclareRoles </li>
     * <li> MultiPart </li>
     * <li> ServletSecurity</li>
     * </ul>
     *
     * @param o the object to introspect
     * @param metaInfo information about the object to introspect
     */
    protected void introspect(Object o, Object metaInfo)
    {
        if (o == null)
            return;
        _introspector.introspect(o, metaInfo);
    }

    @Override
    public Object decorate(Object o)
    {
        introspect(o, DecoratedObjectFactory.getAssociatedInfo());
        return o;
    }

    @Override
    public void destroy(Object o)
    {

    }
}
