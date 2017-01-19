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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Basic Graph Node
 * @param <T> the node type
 */
public abstract class Node<T>
{
    /** The logical name of this Node */
    private String logicalName;
    /** The depth of the Node in the tree */
    private int depth = 0;
    /** The set of selections for how this node was selected */
    private Set<Selection> selections = new LinkedHashSet<>();
    /** Set of Nodes, by name, that this Node depends on */
    private List<String> parentNames = new ArrayList<>();
    /** Set of Nodes, by name, that this Node optionally depend on */
    private List<String> optionalParentNames = new ArrayList<>();

    /** The Edges to parent Nodes */
    private Set<T> parentEdges = new LinkedHashSet<>();
    /** The Edges to child Nodes */
    private Set<T> childEdges = new LinkedHashSet<>();

    public void addChildEdge(T child)
    {
        if (childEdges.contains(child))
        {
            // already present, skip
            return;
        }
        this.childEdges.add(child);
    }

    public void addOptionalParentName(String name)
    {
        if (this.optionalParentNames.contains(name))
        {
            // skip, name already exists
            return;
        }
        this.optionalParentNames.add(name);
    }

    public void addParentEdge(T parent)
    {
        if (parentEdges.contains(parent))
        {
            // already present, skip
            return;
        }
        this.parentEdges.add(parent);
    }

    public void addParentName(String name)
    {
        if (this.parentNames.contains(name))
        {
            // skip, name already exists
            return;
        }
        this.parentNames.add(name);
    }

    public void addSelection(Selection selection)
    {
        this.selections.add(selection);
    }

    public Set<T> getChildEdges()
    {
        return childEdges;
    }

    public int getDepth()
    {
        return depth;
    }

    @Deprecated
    public String getLogicalName()
    {
        return logicalName;
    }

    public String getName()
    {
        return logicalName;
    }

    public List<String> getOptionalParentNames()
    {
        return optionalParentNames;
    }

    public Set<T> getParentEdges()
    {
        return parentEdges;
    }

    public List<String> getParentNames()
    {
        return parentNames;
    }

    public Set<Selection> getSelections()
    {
        return selections;
    }

    public Set<String> getSelectedCriteriaSet()
    {
        Set<String> criteriaSet = new HashSet<>();
        for (Selection selection : selections)
        {
            criteriaSet.add(selection.getCriteria());
        }
        return criteriaSet;
    }

    public boolean isSelected()
    {
        return !selections.isEmpty();
    }

    public boolean matches(Predicate predicate)
    {
        return predicate.match(this);
    }

    public void setDepth(int depth)
    {
        this.depth = depth;
    }

    public void setName(String name)
    {
        this.logicalName = name;
    }

    public void setParentNames(List<String> parents)
    {
        this.parentNames.clear();
        this.parentEdges.clear();
        if (parents != null)
        {
            this.parentNames.addAll(parents);
        }
    }

    public void setSelections(Set<Selection> selection)
    {
        this.selections = selection;
    }
}
