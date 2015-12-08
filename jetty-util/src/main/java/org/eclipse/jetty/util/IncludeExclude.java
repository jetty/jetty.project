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

package org.eclipse.jetty.util;

import java.util.HashSet;
import java.util.Set;


/** Utility class to maintain a set of inclusions and exclusions.
 * <p>Maintains a set of included and excluded elements.  The method {@link #matches(Object)}
 * will return true IFF the passed object is not in the excluded set AND ( either the 
 * included set is empty OR the object is in the included set) 
 * <p>The type of the underlying {@link Set} used may be passed into the 
 * constructor, so special sets like Servlet PathMap may be used.
 * <p>
 * @param <ITEM> The type of element
 */
public class IncludeExclude<ITEM> 
{
    public interface MatchSet<ITEM> extends Set<ITEM>
    {
        public boolean matches(ITEM item);
    }
    
    @SuppressWarnings("serial")
    protected static class ContainsMatchSet<ITEM> extends HashSet<ITEM> implements MatchSet<ITEM>
    {
        @Override
        public boolean matches(ITEM item)
        {
            return contains(item);
        }
    }
    
    private final MatchSet<ITEM> _includes;
    private final MatchSet<ITEM> _excludes;

    /**
     * Default constructor over {@link HashSet}
     */
    public IncludeExclude()
    {
        _includes = new ContainsMatchSet<ITEM>();
        _excludes = new ContainsMatchSet<ITEM>();
    }
    
    /**
     * Construct an IncludeExclude
     * @param setClass The type of {@link Set} to using internally
     * @param matcher A function to test if a passed ITEM is matched by the passed SET, or null to use {@link Set#contains(Object)}
     */
    public IncludeExclude(Class<? extends MatchSet<ITEM>> setClass)
    {
        try
        {
            _includes = setClass.newInstance();
            _excludes = setClass.newInstance();
        }
        catch (InstantiationException | IllegalAccessException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void include(ITEM element)
    {
        _includes.add(element);
    }
    
    public void include(ITEM... element)
    {
        for (ITEM e: element)
            _includes.add(e);
    }

    public void exclude(ITEM element)
    {
        _excludes.add(element);
    }
    
    public void exclude(ITEM... element)
    {
        for (ITEM e: element)
            _excludes.add(e);
    }
    
    public boolean matches(ITEM e)
    {
        if (_includes.size()>0 && !_includes.matches(e))
            return false;
        return !_excludes.matches(e);
    }

    public int size()
    {
        return _includes.size()+_excludes.size();
    }
    
    public Set<ITEM> getIncluded()
    {
        return _includes;
    }
    
    public Set<ITEM> getExcluded()
    {
        return _excludes;
    }

    public void clear()
    {
        _includes.clear();
        _excludes.clear();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{i=%s,e=%s}",this.getClass().getSimpleName(),hashCode(),_includes,_excludes);
    }
}
