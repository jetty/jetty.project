//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http.pathmap;

import java.util.Objects;

public abstract class AbstractPathSpec implements PathSpec
{
    @Override
    public int compareTo(PathSpec other)
    {
        // Grouping (increasing)
        int diff = getGroup().ordinal() - other.getGroup().ordinal();
        if (diff != 0)
            return diff;

        // Spec Length (decreasing)
        diff = other.getSpecLength() - getSpecLength();
        if (diff != 0)
            return diff;

        // Path Spec Name (alphabetical)
        diff = getDeclaration().compareTo(other.getDeclaration());
        if (diff != 0)
            return diff;

        // Path Implementation
        return getClass().getName().compareTo(other.getClass().getName());
    }

    @Override
    public final boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;

        return compareTo((AbstractPathSpec)obj) == 0;
    }

    @Override
    public final int hashCode()
    {
        return Objects.hash(getGroup().ordinal(), getSpecLength(), getDeclaration(), getClass().getName());
    }

    @Override
    public String toString()
    {
        return String.format("%s@%s{%s}", getClass().getSimpleName(), Integer.toHexString(hashCode()), getDeclaration());
    }
}
