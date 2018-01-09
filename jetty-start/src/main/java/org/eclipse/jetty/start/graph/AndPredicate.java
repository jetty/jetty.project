//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
 * Match on multiple predicates.
 */
public class AndPredicate implements Predicate
{
    private final Predicate predicates[];

    public AndPredicate(Predicate... predicates)
    {
        this.predicates = predicates;
    }

    @Override
    public boolean match(Node<?> node)
    {
        for (Predicate predicate : this.predicates)
        {
            if (!predicate.match(node))
            {
                return false;
            }
        }

        return true;
    }
}