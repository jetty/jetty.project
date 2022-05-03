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

package org.eclipse.jetty.ee9.webapp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.util.resource.Resource;

/**
 * AbsoluteOrdering
 */
public class AbsoluteOrdering implements Ordering
{
    public static final String OTHER = "@@-OTHER-@@";
    protected List<String> _order = new ArrayList<String>();
    protected boolean _hasOther = false;
    protected MetaData _metaData;

    public AbsoluteOrdering(MetaData metaData)
    {
        _metaData = metaData;
    }

    @Override
    public List<Resource> order(List<Resource> jars)
    {
        List<Resource> orderedList = new ArrayList<Resource>();
        List<Resource> tmp = new ArrayList<Resource>(jars);

        //1. put everything into the list of named others, and take the named ones out of there,
        //assuming we will want to use the <other> clause
        Map<String, FragmentDescriptor> others = new HashMap<String, FragmentDescriptor>(_metaData.getNamedFragmentDescriptors());

        //2. for each name, take out of the list of others, add to tail of list
        int index = -1;
        for (String item : _order)
        {
            if (!item.equals(OTHER))
            {
                FragmentDescriptor f = others.remove(item);
                if (f != null)
                {
                    Resource jar = _metaData.getJarForFragmentName(item);
                    orderedList.add(jar); //take from others and put into final list in order, ignoring duplicate names
                    //remove resource from list for resource matching name of descriptor
                    tmp.remove(jar);
                }
            }
            else
                index = orderedList.size(); //remember the index at which we want to add in all the others
        }

        //3. if <other> was specified, insert rest of the fragments 
        if (_hasOther)
        {
            orderedList.addAll((index < 0 ? 0 : index), tmp);
        }

        return orderedList;
    }

    public void add(String name)
    {
        _order.add(name);
    }

    public void addOthers()
    {
        if (_hasOther)
            throw new IllegalStateException("Duplicate <other> element in absolute ordering");

        _hasOther = true;
        _order.add(OTHER);
    }
}
