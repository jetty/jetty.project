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

/**
 * Should match against the provided set of {@link Selection#getHow()} values.
 * <p>
 * Incomplete set is considered to be no-match.
 */
public class HowSetPredicate implements Predicate
{
    private final Set<String> howSet;

    public HowSetPredicate(String... hows)
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

        Set<String> actualHows = node.getSelectedHowSet();

        if (actualHows.size() != howSet.size())
        {
            // non-equal sized set
            return false;
        }

        for (String how : actualHows)
        {
            if (!this.howSet.contains(how))
            {
                return false;
            }
        }
        return true;
    }

}