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
 * Represents a selection criteria.
 * <p>
 * Each <code>Selection</code> can be used [0..n] times in the graph. The <code>Selection</code> must contain a unique
 * 'criteria' description that how selection was determined.
 */
public class Selection
{
    private final boolean explicit;
    private final String criteria;

    public Selection(String criteria)
    {
        this(criteria,true);
    }

    /**
     * The Selection criteria
     * 
     * @param criteria
     *            the selection criteria
     * @param explicit
     *            true if explicitly selected, false if transitively selected.
     */
    public Selection(String criteria, boolean explicit)
    {
        this.criteria = criteria;
        this.explicit = explicit;
    }

    public Selection asTransitive()
    {
        if (this.explicit)
        {
            return new Selection(criteria,false);
        }
        return this;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (obj == null)
        {
            return false;
        }
        if (getClass() != obj.getClass())
        {
            return false;
        }
        Selection other = (Selection)obj;
        if (explicit != other.explicit)
        {
            return false;
        }
        if (criteria == null)
        {
            if (other.criteria != null)
            {
                return false;
            }
        }
        else if (!criteria.equals(other.criteria))
        {
            return false;
        }
        return true;
    }

    /**
     * Get the criteria for this selection
     * @return the criteria
     */
    public String getCriteria()
    {
        return criteria;
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = (prime * result) + (explicit ? 1231 : 1237);
        result = (prime * result) + ((criteria == null) ? 0 : criteria.hashCode());
        return result;
    }

    public boolean isExplicit()
    {
        return explicit;
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        if (!explicit)
        {
            str.append("<transitive from> ");
        }
        str.append(criteria);
        return str.toString();
    }
}
