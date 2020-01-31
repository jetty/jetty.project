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

import java.net.URI;

import org.eclipse.jetty.util.Decorator;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.FragmentDescriptor;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebDescriptor;

/**
 * AnnotationDecorator
 */
public class AnnotationDecorator implements Decorator
{
    private static final Logger LOG = Log.getLogger(AnnotationDecorator.class);
    protected AnnotationIntrospector _introspector = new AnnotationIntrospector();
    protected WebAppContext _context;

    public AnnotationDecorator(WebAppContext context)
    {
        _context = context;
        if (!_context.getMetaData().isMetaDataComplete())
        {
            registerHandlers(_context);
        }
    }

    public void registerHandlers(WebAppContext context)
    {
        _introspector.registerHandler(new ResourceAnnotationHandler(context));
        _introspector.registerHandler(new ResourcesAnnotationHandler(context));
        _introspector.registerHandler(new RunAsAnnotationHandler(context));
        _introspector.registerHandler(new PostConstructAnnotationHandler(context));
        _introspector.registerHandler(new PreDestroyAnnotationHandler(context));
        _introspector.registerHandler(new DeclareRolesAnnotationHandler(context));
        _introspector.registerHandler(new MultiPartConfigAnnotationHandler(context));
        _introspector.registerHandler(new ServletSecurityAnnotationHandler(context));
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
     */
    protected void introspect(Object o)
    {
        if (o == null)
            return;
        _introspector.introspect(o.getClass());
    }

    @Override
    public Object decorate(Object o)
    {
        if (o == null)
            return o;

        boolean introspect = false;
        URI location = TypeUtil.getLocationOfClass(o.getClass());
        if (location == null)
        {
            //if can't get the location we introspect because there is no descriptor
            introspect = true;
        }
        else
        {
            try
            {
                //Servlet 4:
                //if the class came from a jar with a web-fragment.xml, we only
                //introspect if that fragment is not metadata-complete
                Resource resource = Resource.newResource(location);
                FragmentDescriptor fragment = _context.getMetaData().getFragmentDescriptorForJar(resource);
                if (fragment == null || !WebDescriptor.isMetaDataComplete(fragment))
                    introspect = true;
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
        }
        if (introspect)
            introspect(o);
        return o;
    }

    @Override
    public void destroy(Object o)
    {

    }
}
