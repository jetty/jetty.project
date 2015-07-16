package org.eclipse.jetty.util;

import java.util.HashSet;
import java.util.Set;

public class IncludeExclude<E> 
{
    private final Set<E> _includes;
    private final Set<E> _excludes;

    public IncludeExclude()
    {
        _includes = new HashSet<>();
        _excludes = new HashSet<>();
    }
    
    public IncludeExclude(Class<? extends Set<E>> setClass)
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
    
    public IncludeExclude(Set<E> includes, Set<E> excludes)
    {
        _includes = includes;
        _excludes = excludes;
    }

    public void include(E element)
    {
        _includes.add(element);
    }
    
    public void include(E... element)
    {
        for (E e: element)
            _includes.add(e);
    }

    public void exclude(E element)
    {
        _excludes.add(element);
    }
    
    public void exclude(E... element)
    {
        for (E e: element)
            _excludes.add(e);
    }
    
    public boolean contains(E e)
    {
        if (_includes.size()>0 && !_includes.contains(e))
            return false;
        return !_excludes.contains(e);
    }

    public Set<E> getIncluded()
    {
        return _includes;
    }
    
    public Set<E> getExcluded()
    {
        return _excludes;
    }

    public void clear()
    {
        _includes.clear();
        _excludes.clear();
    }

}
