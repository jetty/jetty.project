//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import java.util.Set;
import java.util.function.Predicate;


/** Utility class to maintain a set of inclusions and exclusions.
 * <p>This extension of the {@link IncludeExcludeSet} class is used
 * when the type of the set elements is the same as the type of 
 * the predicate test.
 * <p>
 * @param <ITEM> The type of element
 */
public class IncludeExclude<ITEM> extends IncludeExcludeSet<ITEM,ITEM>
{
    public IncludeExclude()
    {
        super();
    }

    public <SET extends Set<ITEM>> IncludeExclude(Class<SET> setClass)
    {
        super(setClass);
    }

    public <SET extends Set<ITEM>> IncludeExclude(Set<ITEM> includeSet, Predicate<ITEM> includePredicate, Set<ITEM> excludeSet,
            Predicate<ITEM> excludePredicate)
    {
        super(includeSet,includePredicate,excludeSet,excludePredicate);
    }
}
