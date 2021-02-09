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

package org.eclipse.jetty.webapp;

import java.util.Map;

import org.eclipse.jetty.util.resource.Resource;

/**
 * FragmentConfiguration
 * <p>
 * Process web-fragments in jars
 */
public class FragmentConfiguration extends AbstractConfiguration
{
    public static final String FRAGMENT_RESOURCES = "org.eclipse.jetty.webFragments";

    @Override
    public void preConfigure(WebAppContext context) throws Exception
    {
        //add all discovered web-fragment.xmls
        addWebFragments(context, context.getMetaData());
    }

    @Override
    public void postConfigure(WebAppContext context) throws Exception
    {
        context.setAttribute(FRAGMENT_RESOURCES, null);
    }

    /**
     * Add in fragment descriptors that have already been discovered by MetaInfConfiguration
     *
     * @param context the web app context to look in
     * @param metaData the metadata to populate with fragments
     * @throws Exception if unable to find web fragments
     * @deprecated
     */
    public void findWebFragments(final WebAppContext context, final MetaData metaData)
        throws Exception
    {
        addWebFragments(context, metaData);
    }

    /**
     * Add in fragment descriptors that have already been discovered by MetaInfConfiguration
     *
     * @param context the web app context to look in
     * @param metaData the metadata to populate with fragments
     * @throws Exception if unable to find web fragments
     */
    public void addWebFragments(final WebAppContext context, final MetaData metaData) throws Exception
    {
        @SuppressWarnings("unchecked")
        Map<Resource, Resource> frags = (Map<Resource, Resource>)context.getAttribute(FRAGMENT_RESOURCES);
        if (frags != null)
        {
            for (Map.Entry<Resource, Resource> entry : frags.entrySet())
            {
                metaData.addFragment(entry.getKey(), entry.getValue());
            }
        }
    }
}
