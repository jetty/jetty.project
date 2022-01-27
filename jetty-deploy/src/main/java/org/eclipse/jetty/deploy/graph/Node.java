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
            if (other._name != null)
                return false;
        }
        else if (!_name.equals(other._name))
            return false;
        return true;
    }
}
