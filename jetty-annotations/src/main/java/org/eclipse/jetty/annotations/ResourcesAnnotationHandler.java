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

import javax.naming.NamingException;

import jakarta.annotation.Resource;
import jakarta.annotation.Resources;
import org.eclipse.jetty.annotations.AnnotationIntrospector.AbstractIntrospectableAnnotationHandler;
import org.eclipse.jetty.webapp.WebAppContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResourcesAnnotationHandler extends AbstractIntrospectableAnnotationHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(ResourcesAnnotationHandler.class);

    public ResourcesAnnotationHandler(WebAppContext wac)
    {
        super(true, wac);
    }

    @Override
    public void doHandle(Class<?> clazz)
    {
        Resources resources = (Resources)clazz.getAnnotation(Resources.class);
        if (resources != null)
        {
            Resource[] resArray = resources.value();
            if (resArray == null || resArray.length == 0)
            {
                LOG.warn("Skipping empty or incorrect Resources annotation on {}", clazz.getName());
                return;
            }

            for (int j = 0; j < resArray.length; j++)
            {
                String name = resArray[j].name();
                String mappedName = resArray[j].mappedName();

                if (name == null || name.trim().equals(""))
                    throw new IllegalStateException("Class level Resource annotations must contain a name (Common Annotations Spec Section 2.3)");

                try
                {
                    //TODO don't ignore the shareable, auth etc etc

                    if (!org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_context, name, mappedName))
                        if (!org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_context.getServer(), name, mappedName))
                            LOG.warn("Skipping Resources(Resource) annotation on {} for name {}: no resource bound at {}",
                                    clazz.getName(), name, (mappedName == null ? name : mappedName));
                }
                catch (NamingException e)
                {
                    LOG.warn("Unable to bind {} to {}", name, mappedName, e);
                }
            }
        }
    }
}
