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

import javax.servlet.Servlet;

import org.eclipse.jetty.annotations.AnnotationIntrospector.AbstractIntrospectableAnnotationHandler;
import org.eclipse.jetty.plus.annotation.RunAsCollection;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.Descriptor;
import org.eclipse.jetty.webapp.MetaData;
import org.eclipse.jetty.webapp.WebAppContext;


public class RunAsAnnotationHandler extends AbstractIntrospectableAnnotationHandler
{
    private static final Logger LOG = Log.getLogger(RunAsAnnotationHandler.class);

    protected WebAppContext _context;

    public RunAsAnnotationHandler (WebAppContext wac)
    {
        //Introspect only the given class for a RunAs annotation, as it is a class level annotation,
        //and according to Common Annotation Spec p2-6 a class-level annotation is not inheritable.
        super(false);
        _context = wac;
    }

    public void doHandle (Class clazz)
    {
        if (!Servlet.class.isAssignableFrom(clazz))
            return;

        javax.annotation.security.RunAs runAs = (javax.annotation.security.RunAs)clazz.getAnnotation(javax.annotation.security.RunAs.class);
        if (runAs != null)
        {
            String role = runAs.value();
            if (role != null)
            {
                ServletHolder holder = getServletHolderForClass(clazz);
                if (holder != null)
                {
                    MetaData metaData = _context.getMetaData();
                    Descriptor d = metaData.getOriginDescriptor(holder.getName()+".servlet.run-as");
                    //if a descriptor has already set the value for run-as, do not
                    //let the annotation override it
                    if (d == null)
                    {
                        metaData.setOrigin(holder.getName()+".servlet.run-as",runAs,clazz);
                        org.eclipse.jetty.plus.annotation.RunAs ra = new org.eclipse.jetty.plus.annotation.RunAs();
                        ra.setTargetClassName(clazz.getCanonicalName());
                        ra.setRoleName(role);
                        RunAsCollection raCollection = (RunAsCollection)_context.getAttribute(RunAsCollection.RUNAS_COLLECTION);
                        if (raCollection == null)
                        {
                            raCollection = new RunAsCollection();
                            _context.setAttribute(RunAsCollection.RUNAS_COLLECTION, raCollection);
                        }
                        raCollection.add(ra);
                    }
                }
            }
            else
                LOG.warn("Bad value for @RunAs annotation on class "+clazz.getName());
        }

    }

    public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation)
    {
       LOG.warn ("@RunAs annotation not applicable for fields: "+className+"."+fieldName);
    }

    public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation)
    {
        LOG.warn("@RunAs annotation ignored on method: "+className+"."+methodName+" "+signature);
    }

    private ServletHolder getServletHolderForClass (Class clazz)
    {
        ServletHolder holder = null;
        ServletHolder[] holders = _context.getServletHandler().getServlets();
        if (holders != null)
        {
            for (ServletHolder h : holders)
            {
                if (h.getClassName() != null && h.getClassName().equals(clazz.getName()))
                {
                    holder = h;
                }
            }
        }
        return holder;
    }
}
