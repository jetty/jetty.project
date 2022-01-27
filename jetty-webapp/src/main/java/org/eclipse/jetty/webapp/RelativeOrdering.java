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

package org.eclipse.jetty.webapp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.jetty.util.TopologicalSort;
import org.eclipse.jetty.util.resource.Resource;

/**
 * Relative Fragment Ordering
 * <p>Uses a {@link TopologicalSort} to order the fragments.</p>
 */
public class RelativeOrdering implements Ordering
{
    protected MetaData _metaData;

    public RelativeOrdering(MetaData metaData)
    {
        _metaData = metaData;
    }

    @Override
    public List<Resource> order(List<Resource> jars)
    {
        TopologicalSort<Resource> sort = new TopologicalSort<>();
        List<Resource> sorted = new ArrayList<>(jars);
        Set<Resource> others = new HashSet<>();
        Set<Resource> beforeOthers = new HashSet<>();
        Set<Resource> afterOthers = new HashSet<>();

        // Pass 1: split the jars into 'before others', 'others' or 'after others'
        for (Resource jar : jars)
        {
            FragmentDescriptor fragment = _metaData.getFragmentDescriptorForJar(jar);

            if (fragment == null)
                others.add(jar);
            else
            {
                switch (fragment.getOtherType())
                {
                    case None:
                        others.add(jar);
                        break;
                    case Before:
                        beforeOthers.add(jar);
                        break;
                    case After:
                        afterOthers.add(jar);
                        break;
                    default:
                        throw new IllegalStateException(fragment.toString());
                }
            }
        }

        // Pass 2: Add sort dependencies for each jar
        Set<Resource> referenced = new HashSet<>();
        for (Resource jar : jars)
        {
            FragmentDescriptor fragment = _metaData.getFragmentDescriptorForJar(jar);

            if (fragment != null)
            {
                // Add each explicit 'after' ordering as a sort dependency
                // and remember that the dependency has been referenced.
                for (String name : fragment.getAfters())
                {
                    Resource after = _metaData.getJarForFragmentName(name);
                    sort.addDependency(jar, after);
                    referenced.add(after);
                }

                // Add each explicit 'before' ordering as a sort dependency
                // and remember that the dependency has been referenced.
                for (String name : fragment.getBefores())
                {
                    Resource before = _metaData.getJarForFragmentName(name);
                    sort.addDependency(before, jar);
                    referenced.add(before);
                }

                // handle the others
                switch (fragment.getOtherType())
                {
                    case None:
                        break;
                    case Before:
                        // Add a dependency on this jar from all 
                        // jars in the 'others' and 'after others' sets, but
                        // exclude any jars we have already explicitly 
                        // referenced above.
                        Consumer<Resource> addBefore = other ->
                        {
                            if (!referenced.contains(other))
                                sort.addDependency(other, jar);
                        };
                        others.forEach(addBefore);
                        afterOthers.forEach(addBefore);
                        break;

                    case After:
                        // Add a dependency from this jar to all 
                        // jars in the 'before others' and 'others' sets, but
                        // exclude any jars we have already explicitly 
                        // referenced above.
                        Consumer<Resource> addAfter = other ->
                        {
                            if (!referenced.contains(other))
                                sort.addDependency(jar, other);
                        };
                        beforeOthers.forEach(addAfter);
                        others.forEach(addAfter);
                        break;
                    default:
                        throw new IllegalStateException(fragment.toString());
                }
            }
            referenced.clear();
        }

        // sort the jars according to the added dependencies
        sort.sort(sorted);

        return sorted;
    }
}
