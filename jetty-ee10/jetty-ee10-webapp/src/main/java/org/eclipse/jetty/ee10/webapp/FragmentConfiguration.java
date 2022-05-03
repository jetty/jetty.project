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

package org.eclipse.jetty.ee10.webapp;

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

    public FragmentConfiguration()
    {
        addDependencies(MetaInfConfiguration.class, WebXmlConfiguration.class);
    }

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
     */
    public void addWebFragments(final WebAppContext context, final MetaData metaData) throws Exception
    {
        @SuppressWarnings("unchecked")
        Map<Resource, Resource> frags = (Map<Resource, Resource>)context.getAttribute(FRAGMENT_RESOURCES);
        if (frags != null)
        {
            for (Map.Entry<Resource, Resource> entry : frags.entrySet())
            {
                metaData.addFragmentDescriptor(entry.getKey(), new FragmentDescriptor(entry.getValue()));
            }
        }
    }
}
