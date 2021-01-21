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

import javax.annotation.security.DeclareRoles;
import javax.servlet.Servlet;

import org.eclipse.jetty.annotations.AnnotationIntrospector.AbstractIntrospectableAnnotationHandler;
import org.eclipse.jetty.security.ConstraintAware;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * DeclaresRolesAnnotationHandler
 */
public class DeclareRolesAnnotationHandler extends AbstractIntrospectableAnnotationHandler
{
    private static final Logger LOG = Log.getLogger(DeclareRolesAnnotationHandler.class);

    protected WebAppContext _context;

    public DeclareRolesAnnotationHandler(WebAppContext context)
    {
        super(false);
        _context = context;
    }

    /**
     * @see org.eclipse.jetty.annotations.AnnotationIntrospector.AbstractIntrospectableAnnotationHandler#doHandle(java.lang.Class)
     */
    @Override
    public void doHandle(Class clazz)
    {
        if (!Servlet.class.isAssignableFrom(clazz))
            return; //only applicable on javax.servlet.Servlet derivatives

        if (!(_context.getSecurityHandler() instanceof ConstraintAware))
        {
            LOG.warn("SecurityHandler not ConstraintAware, skipping security annotation processing");
            return;
        }

        DeclareRoles declareRoles = (DeclareRoles)clazz.getAnnotation(DeclareRoles.class);
        if (declareRoles == null)
            return;

        String[] roles = declareRoles.value();

        if (roles != null && roles.length > 0)
        {
            for (String r : roles)
            {
                ((ConstraintSecurityHandler)_context.getSecurityHandler()).addRole(r);
            }
        }
    }
}
