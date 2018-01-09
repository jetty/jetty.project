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
 * Predicate against a specific {@link Selection#getCriteria()}
 */
public class CriteriaPredicate implements Predicate
{
    private final String criteria;

    public CriteriaPredicate(String criteria)
    {
        this.criteria = criteria;
    }

    @Override
    public boolean match(Node<?> node)
    {
        for (Selection selection : node.getSelections())
        {
            if (criteria.equalsIgnoreCase(selection.getCriteria()))
            {
                return true;
            }
        }
        return false;
    }
}