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

import javax.annotation.security.DeclareRoles;
import javax.servlet.Servlet;

import org.eclipse.jetty.annotations.AnnotationIntrospector.AbstractIntrospectableAnnotationHandler;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * DeclaresRolesAnnotationHandler
 */
public class DeclareRolesAnnotationHandler extends AbstractIntrospectableAnnotationHandler
{

    protected WebAppContext _context;

    public DeclareRolesAnnotationHandler(WebAppContext context)
    {
        super(false);
        _context = context;
    }


    /**
     * @see org.eclipse.jetty.annotations.AnnotationIntrospector.AbstractIntrospectableAnnotationHandler#doHandle(java.lang.Class)
     */
    public void doHandle(Class clazz)
    {
        if (!Servlet.class.isAssignableFrom(clazz))
            return; //only applicable on javax.servlet.Servlet derivatives

        DeclareRoles declareRoles = (DeclareRoles) clazz.getAnnotation(DeclareRoles.class);
        if (declareRoles == null)
            return;

        String[] roles = declareRoles.value();

        if (roles != null && roles.length > 0)
        {
            for (String r:roles)
                ((ConstraintSecurityHandler)_context.getSecurityHandler()).addRole(r);
        }
    }

}
