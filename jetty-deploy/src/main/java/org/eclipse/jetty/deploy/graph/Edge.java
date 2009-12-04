// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.deploy.graph;

/**
 * Basic Graph Edge
 */
public class Edge
{
    private Node from;
    private Node to;

    public Edge(Node from, Node to)
    {
        this.from = from;
        this.to = to;
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((from == null)?0:from.hashCode());
        result = prime * result + ((to == null)?0:to.hashCode());
        return result;
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
        if (from == null)
        {
            if (other.from != null)
                return false;
        }
        else if (!from.equals(other.from))
            return false;
        if (to == null)
        {
            if (other.to != null)
                return false;
        }
        else if (!to.equals(other.to))
            return false;
        return true;
    }

    public Node getFrom()
    {
        return from;
    }

    public Node getTo()
    {
        return to;
    }
}