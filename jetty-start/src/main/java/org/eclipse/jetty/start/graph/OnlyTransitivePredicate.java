//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
 * Predicate for a node that has no explicitly set selections.
 * (They are all transitive)
 */
public class OnlyTransitivePredicate implements Predicate
{
    public static final Predicate INSTANCE = new OnlyTransitivePredicate();
    
    @Override
    public boolean match(Node<?> input)
    {
        for (Selection selection : input.getSelections())
        {
            if (selection.isExplicit())
            {
                return false;
            }
        }
        return true;
    }
}
