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

import javax.servlet.MultipartConfigElement;
import javax.servlet.Servlet;
import javax.servlet.annotation.MultipartConfig;

import org.eclipse.jetty.annotations.AnnotationIntrospector.AbstractIntrospectableAnnotationHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.webapp.Descriptor;
import org.eclipse.jetty.webapp.MetaData;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * MultiPartConfigAnnotationHandler
 */
public class MultiPartConfigAnnotationHandler extends AbstractIntrospectableAnnotationHandler
{
    protected WebAppContext _context;

    public MultiPartConfigAnnotationHandler(WebAppContext context)
    {
        //TODO verify that MultipartConfig is not inheritable
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
