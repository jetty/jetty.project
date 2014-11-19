//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.start.graph;

import java.util.HashSet;
import java.util.Set;

public class HowSetMatcher implements Predicate
{
    private final Set<String> howSet;
    
    public HowSetMatcher(String... hows)
    {
        this.howSet = new HashSet<>();

        for (String name : hows)
        {
            this.howSet.add(name);
        }
    }

    @Override
    public boolean match(Node<?> node)
    {
        Set<Selection> selections = node.getSelections();
        if (selections == null)
        {
            // empty sources list
            return false;
        }

        if (selections.size() != howSet.size())
        {
            // non-equal sized set
            return false;
        }

        for (Selection selection : selections)
        {
            if (!this.howSet.contains(selection.getHow()))
            {
                return false;
            }
        }
        return true;
    }
    
}