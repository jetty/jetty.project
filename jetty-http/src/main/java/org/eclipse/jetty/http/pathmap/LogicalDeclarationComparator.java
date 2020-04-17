//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Comparator;

/**
 * Sort {@link MappedResource}s by their {@link MappedResource#getPathSpec()} logical declarations.
 */
public class LogicalDeclarationComparator implements Comparator<MappedResource>
{
    public static final LogicalDeclarationComparator INSTANCE = new LogicalDeclarationComparator();

    @Override
    public int compare(MappedResource o1, MappedResource o2)
    {
        return o1.getPathSpec().compareTo(o2.getPathSpec());
    }
}
