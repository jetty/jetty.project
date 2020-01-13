//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.annotations;

import javax.annotation.Resource;
import javax.annotation.Resources;
import javax.naming.NamingException;

import org.eclipse.jetty.annotations.AnnotationIntrospector.AbstractIntrospectableAnnotationHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppContext;

public class ResourcesAnnotationHandler extends AbstractIntrospectableAnnotationHandler
{
    private static final Logger LOG = Log.getLogger(ResourcesAnnotationHandler.class);

    protected WebAppContext _wac;

    public ResourcesAnnotationHandler(WebAppContext wac)
    {
        super(true);
        _wac = wac;
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
                LOG.warn("Skipping empty or incorrect Resources annotation on " + clazz.getName());
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

                    if (!org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_wac, name, mappedName))
                        if (!org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_wac.getServer(), name, mappedName))
                            LOG.warn("Skipping Resources(Resource) annotation on " + clazz.getName() + " for name " + name + ": No resource bound at " + (mappedName == null ? name : mappedName));
                }
                catch (NamingException e)
                {
                    LOG.warn(e);
                }
            }
        }
    }
}
