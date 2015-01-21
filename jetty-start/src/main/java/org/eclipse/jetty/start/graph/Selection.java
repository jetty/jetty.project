//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

public class Selection
{
    private final boolean explicit;
    private final String how;

    public Selection(String how)
    {
        this(how,true);
    }

    public Selection(String how, boolean explicit)
    {
        this.how = how;
        this.explicit = explicit;
    }

    public Selection asTransitive()
    {
        if (this.explicit)
        {
            return new Selection(how,false);
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
        if (how == null)
        {
            if (other.how != null)
            {
                return false;
            }
        }
        else if (!how.equals(other.how))
        {
            return false;
        }
        return true;
    }

    public String getHow()
    {
        return how;
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = (prime * result) + (explicit?1231:1237);
        result = (prime * result) + ((how == null)?0:how.hashCode());
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
        str.append(how);
        return str.toString();
    }
}
