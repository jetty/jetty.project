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

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.Servlet;
import jakarta.servlet.annotation.MultipartConfig;
import org.eclipse.jetty.ee9.annotations.AnnotationIntrospector.AbstractIntrospectableAnnotationHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.ee9.webapp.Descriptor;
import org.eclipse.jetty.ee9.webapp.MetaData;
import org.eclipse.jetty.ee9.webapp.WebAppContext;

/**
 * MultiPartConfigAnnotationHandler
 */
public class MultiPartConfigAnnotationHandler extends AbstractIntrospectableAnnotationHandler
{
    public MultiPartConfigAnnotationHandler(WebAppContext context)
    {
        //TODO verify that MultipartConfig is not inheritable
        super(false, context);
    }

    @Override
    public void doHandle(Class clazz)
    {
        if (!Servlet.class.isAssignableFrom(clazz))
            return;

        MultipartConfig multi = (MultipartConfig)clazz.getAnnotation(MultipartConfig.class);
        if (multi == null)
            return;

        MetaData metaData = _context.getMetaData();

        //TODO: The MultipartConfigElement needs to be set on the ServletHolder's Registration.
        //How to identify the correct Servlet?  If the Servlet has no WebServlet annotation on it, does it mean that this MultipartConfig
        //annotation applies to all declared instances in web.xml/programmatically?
        //Assuming TRUE for now.
        for (ServletHolder holder : _context.getServletHandler().getServlets(clazz))
        {
            Descriptor d = metaData.getOriginDescriptor(holder.getName() + ".servlet.multipart-config");
            //if a descriptor has already set the value for multipart config, do not 
            //let the annotation override it
            if (d == null)
            {
                metaData.setOrigin(holder.getName() + ".servlet.multipart-config", multi, clazz);
                holder.getRegistration().setMultipartConfig(new MultipartConfigElement(multi));
            }
        }
    }
}
