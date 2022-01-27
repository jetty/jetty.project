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

package org.eclipse.jetty.util;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Utility class to maintain a set of inclusions and exclusions.
 * <p>This extension of the {@link IncludeExcludeSet} class is used
 * when the type of the set elements is the same as the type of
 * the predicate test.
 * <p>
 *
 * @param <ITEM> The type of element
 */
public class IncludeExclude<ITEM> extends IncludeExcludeSet<ITEM, ITEM>
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
        super(includeSet, includePredicate, excludeSet, excludePredicate);
    }
}
