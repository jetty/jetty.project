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

/**
 * Match against a specific {@link Selection#getHow()}, where
 * there are no other {@link Selection#isExplicit()} specified.
 */
public class HowUniquePredicate implements Predicate
{
    private final String how;

    public HowUniquePredicate(String how)
    {
        this.how = how;
    }

    @Override
    public boolean match(Node<?> node)
    {
        if (node.getSelections().isEmpty())
        {
            // Empty selection list (no uniqueness to it)
            return false;
        }
        
        // Assume no match
        boolean ret = false;
        
        for (Selection selection : node.getSelections())
        {
            if (how.equalsIgnoreCase(selection.getHow()))
            {
                // Found a match
                ret = true;
                continue; // this 'how' is always valid.
            }
            else if (selection.isExplicit())
            {
                // Automatic failure
                return false;
            }
        }

        return ret;
    }
}