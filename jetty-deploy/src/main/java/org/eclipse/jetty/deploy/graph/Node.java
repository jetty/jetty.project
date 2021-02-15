//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.deploy.graph;

/**
 * Basic Graph Node
 */
public final class Node
{
    private final String _name;

    public Node(String name)
    {
        assert name != null;
        this._name = name;
    }

    public String getName()
    {
        return _name;
    }

    @Override
    public String toString()
    {
        return "Node[" + _name + "]";
    }

    @Override
    public int hashCode()
    {
        return _name.hashCode();
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
        Node other = (Node)obj;
        if (_name == null)
        {
            return other._name == null;
        }
        else
            return _name.equals(other._name);
    }
}
