//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
 * Should match against the provided set of {@link Selection#getCriteria()} values.
 * <p>
 * Incomplete set is considered to be no-match.
 */
public class CriteriaSetPredicate implements Predicate
{
    private final Set<String> criteriaSet;

    public CriteriaSetPredicate(String... criterias)
    {
        this.criteriaSet = new HashSet<>();

        for (String name : criterias)
        {
            this.criteriaSet.add(name);
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

        Set<String> actualCriterias = node.getSelectedCriteriaSet();

        if (actualCriterias.size() != criteriaSet.size())
        {
            // non-equal sized set
            return false;
        }

        for (String actualCriteria : actualCriterias)
        {
            if (!this.criteriaSet.contains(actualCriteria))
            {
                return false;
            }
        }
        return true;
    }

}