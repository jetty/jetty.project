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

package org.eclipse.jetty.annotations;

import jakarta.annotation.security.DeclareRoles;
import jakarta.servlet.Servlet;
import org.eclipse.jetty.annotations.AnnotationIntrospector.AbstractIntrospectableAnnotationHandler;
import org.eclipse.jetty.security.ConstraintAware;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.webapp.WebAppContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DeclaresRolesAnnotationHandler
 */
public class DeclareRolesAnnotationHandler extends AbstractIntrospectableAnnotationHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(DeclareRolesAnnotationHandler.class);

    public DeclareRolesAnnotationHandler(WebAppContext context)
    {
        super(false, context);
    }

    @Override
    public void doHandle(Class clazz)
    {
        if (!Servlet.class.isAssignableFrom(clazz))
            return; //only applicable on jakarta.servlet.Servlet derivatives

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
                _context.getMetaData().setOrigin("security-role." + r, declareRoles, clazz);
            }
        }
    }
}
