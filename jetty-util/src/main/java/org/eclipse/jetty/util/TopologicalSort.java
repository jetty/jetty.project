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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Topological sort a list or array.
 * <p>A Topological sort is used when you have a partial ordering expressed as
 * dependencies between elements (also often represented as edges in a directed
 * acyclic graph).  A Topological sort should not be used when you have a total
 * ordering expressed as a {@link Comparator} over the items. The algorithm has
 * the additional characteristic that dependency sets are sorted by the original
 * list order so that order is preserved when possible.</p>
 * <p>
 * The sort algorithm works by recursively visiting every item, once and
 * only once. On each visit, the items dependencies are first visited and then the
 * item is added to the sorted list.  Thus the algorithm ensures that dependency
 * items are always added before dependent items.</p>
 *
 * @param <T> The type to be sorted. It must be able to be added to a {@link HashSet}
 */
public class TopologicalSort<T>
{
    // IMPORTANT NOTE: This class cannot use Logging, as this class is used by jetty-start

    private final Map<T, Set<T>> _dependencies = new HashMap<>();

    /**
     * Add a dependency to be considered in the sort.
     *
     * @param dependent The dependent item will be sorted after all its dependencies
     * @param dependency The dependency item, will be sorted before its dependent item
     */
    public void addDependency(T dependent, T... dependency)
    {
        Set<T> set = _dependencies.get(dependent);
        if (set == null)
        {
            set = new HashSet<>();
            _dependencies.put(dependent, set);
        }
        for (T d : dependency)
        {
            set.add(d);
        }
    }

    /**
     * An alternative to {@link #addDependency(Object, Object[])}, which is
     * equivalent to addDependency(after,before) as the after item is dependent
     * of the before item.
     *
     * @param before The item will be sorted before the after
     * @param after The item will be sorted after the before
     */
    public void addBeforeAfter(T before, T after)
    {
        addDependency(after, before);
    }

    /**
     * Sort the passed array according to dependencies previously set with
     * {@link #addDependency(Object, Object[])}.  Where possible, ordering will be
     * preserved if no dependency
     *
     * @param array The array to be sorted.
     */
    public void sort(T[] array)
    {
        List<T> sorted = new ArrayList<>();
        Set<T> visited = new HashSet<>();
        Comparator<T> comparator = new InitialOrderComparator<>(array);

        // Visit all items in the array
        for (T t : array)
        {
            visit(t, visited, sorted, comparator);
        }

        sorted.toArray(array);
    }

    /**
     * Sort the passed list according to dependencies previously set with
     * {@link #addDependency(Object, Object[])}.  Where possible, ordering will be
     * preserved if no dependency
     *
     * @param list The list to be sorted.
     */
    public void sort(Collection<T> list)
    {
        List<T> sorted = new ArrayList<>();
        Set<T> visited = new HashSet<>();
        Comparator<T> comparator = new InitialOrderComparator<>(list);

        // Visit all items in the list
        for (T t : list)
        {
            visit(t, visited, sorted, comparator);
        }

        list.clear();
        list.addAll(sorted);
    }

    /**
     * Visit an item to be sorted.
     *
     * @param item The item to be visited
     * @param visited The Set of items already visited
     * @param sorted The list to sort items into
     * @param comparator A comparator used to sort dependencies.
     */
    private void visit(T item, Set<T> visited, List<T> sorted, Comparator<T> comparator)
    {
        // If the item has not been visited
        if (!visited.contains(item))
        {
            // We are visiting it now, so add it to the visited set
            visited.add(item);

            // Lookup the items dependencies
            Set<T> dependencies = _dependencies.get(item);
            if (dependencies != null)
            {
                // Sort the dependencies 
                SortedSet<T> orderedDeps = new TreeSet<>(comparator);
                orderedDeps.addAll(dependencies);

                // recursively visit each dependency
                try
                {
                    for (T d : orderedDeps)
                    {
                        visit(d, visited, sorted, comparator);
                    }
                }
                catch (CyclicException e)
                {
                    throw new CyclicException(item, e);
                }
            }

            // Now that we have visited all our dependencies, they and their 
            // dependencies will have been added to the sorted list. So we can
            // now add the current item and it will be after its dependencies
            sorted.add(item);
        }
        else if (!sorted.contains(item))
            // If we have already visited an item, but it has not yet been put in the
            // sorted list, then we must be in a cycle!
            throw new CyclicException(item);
    }

    /**
     * A comparator that is used to sort dependencies in the order they
     * were in the original list.  This ensures that dependencies are visited
     * in the original order and no needless reordering takes place.
     */
    private static class InitialOrderComparator<T> implements Comparator<T>
    {
        private final Map<T, Integer> _indexes = new HashMap<>();

        InitialOrderComparator(T[] initial)
        {
            int i = 0;
            for (T t : initial)
            {
                _indexes.put(t, i++);
            }
        }

        InitialOrderComparator(Collection<T> initial)
        {
            int i = 0;
            for (T t : initial)
            {
                _indexes.put(t, i++);
            }
        }

        @Override
        public int compare(T o1, T o2)
        {
            Integer i1 = _indexes.get(o1);
            Integer i2 = _indexes.get(o2);
            if (i1 == null || i2 == null || i1.equals(o2))
                return 0;
            if (i1 < i2)
                return -1;
            return 1;
        }
    }

    @Override
    public String toString()
    {
        return "TopologicalSort " + _dependencies;
    }

    private static class CyclicException extends IllegalStateException
    {
        CyclicException(Object item)
        {
            super("cyclic at " + item);
        }

        CyclicException(Object item, CyclicException e)
        {
            super("cyclic at " + item, e);
        }
    }
}
