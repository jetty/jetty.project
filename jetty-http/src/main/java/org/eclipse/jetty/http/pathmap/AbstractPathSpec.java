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
