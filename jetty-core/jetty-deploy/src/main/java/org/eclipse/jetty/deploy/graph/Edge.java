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

package org.eclipse.jetty.deploy.graph;

/**
 * Basic Graph Edge
 */
public final class Edge
{
    private Node _from;
    private Node _to;

    public Edge(Node from, Node to)
    {
        @SuppressWarnings("ReferenceEquality")
        boolean sameObject = (from == to);
        if (from == null || to == null || sameObject)
            throw new IllegalArgumentException("from " + from + " to " + to);
        _from = from;
        _to = to;
    }

    @Override
    public int hashCode()
    {
        return _from.hashCode() ^ _to.hashCode();
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        Edge other = (Edge)obj;
        if (_from == null)
        {
            if (other._from != null)
                return false;
        }
        else if (!_from.equals(other._from))
            return false;
        if (_to == null)
        {
            if (other._to != null)
                return false;
        }
        else if (!_to.equals(other._to))
            return false;
        return true;
    }

    public Node getFrom()
    {
        return _from;
    }

    public Node getTo()
    {
        return _to;
    }

    @Override
    public String toString()
    {
        return _from + "->" + _to;
    }
}
