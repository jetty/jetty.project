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

import jakarta.servlet.Servlet;
import org.eclipse.jetty.ee9.annotations.AnnotationIntrospector.AbstractIntrospectableAnnotationHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.ee9.webapp.Descriptor;
import org.eclipse.jetty.ee9.webapp.MetaData;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RunAsAnnotationHandler extends AbstractIntrospectableAnnotationHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(RunAsAnnotationHandler.class);

    public RunAsAnnotationHandler(WebAppContext wac)
    {
        //Introspect only the given class for a RunAs annotation, as it is a class level annotation,
        //and according to Common Annotation Spec p2-6 a class-level annotation is not inheritable.
        super(false, wac);
    }

    @Override
    public void doHandle(Class clazz)
    {
        if (!Servlet.class.isAssignableFrom(clazz))
            return;

        jakarta.annotation.security.RunAs runAs = (jakarta.annotation.security.RunAs)clazz.getAnnotation(jakarta.annotation.security.RunAs.class);
        if (runAs != null)
        {
            String role = runAs.value();
            if (role != null)
            {
                for (ServletHolder holder : _context.getServletHandler().getServlets(clazz))
                {
                    MetaData metaData = _context.getMetaData();
                    Descriptor d = metaData.getOriginDescriptor(holder.getName() + ".servlet.run-as");
                    //if a descriptor has already set the value for run-as, do not
                    //let the annotation override it
                    if (d == null)
                    {
                        metaData.setOrigin(holder.getName() + ".servlet.run-as", runAs, clazz);
                        holder.setRunAsRole(role);
                    }
                }
            }
            else
                LOG.warn("Bad value for @RunAs annotation on class {}", clazz.getName());
        }
    }

    public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation)
    {
        LOG.warn("@RunAs annotation not applicable for fields: {}.{}", className, fieldName);
    }

    public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation)
    {
        LOG.warn("@RunAs annotation ignored on method: {}.{} {}", className, methodName, signature);
    }
}
