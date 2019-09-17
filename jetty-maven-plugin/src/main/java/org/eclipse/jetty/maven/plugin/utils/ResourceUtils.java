//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.maven.plugin.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;

public final class ResourceUtils
{
    private ResourceUtils()
    {

    }

    /**
     * flattens structure of resources - removing nested ResourceCollections
     *
     * @param candidate - candidate
     * @return list of resources
     */
    public static List<Resource> flattenResourceCollection(Resource candidate)
    {
        ArrayList<Resource> resources = new ArrayList<>();
        if (candidate instanceof ResourceCollection)
        {
            ResourceCollection collection = (ResourceCollection)candidate;
            Arrays.stream(collection.getResources())
                .map(ResourceUtils::flattenResourceCollection)
                .forEach(resources::addAll);
        }
        else
        {
            resources.add(candidate);
        }
        return resources;
    }
}
