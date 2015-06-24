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
 * Topological Sort a list or array.
 * <p>The algorithm has the additional characteristic that dependency sets are
 * sorted by the original list order so that order is preserved when possible 
 * @param <T> The type to be sorted.
 */
public class TopologicalSort<T>
{
    private final Map<T,Set<T>> _dependencies = new HashMap<>();

    public void addDependency(T dependent, T dependency)
    {
        Set<T> set = _dependencies.get(dependent);
        if (set==null)
        {
            set=new HashSet<>();
            _dependencies.put(dependent,set);
        }
        set.add(dependency);
    }
    
    public void sort(T[] list)
    {
        List<T> sorted = new ArrayList<>();
        Set<T> visited = new HashSet<>();
        Comparator<T> comparator = new InitialOrderComparitor<>(list);
        for (T t : list)
            visit(t,visited,sorted,comparator);
        
        sorted.toArray(list);
    }
    
    public void sort(Collection<T> list)
    {
        List<T> sorted = new ArrayList<>();
        Set<T> visited = new HashSet<>();
        Comparator<T> comparator = new InitialOrderComparitor<>(list);
        
        for (T t : list)
            visit(t,visited,sorted,comparator);
        
        list.clear();
        list.addAll(sorted);
    }
    
    private void visit(T t, Set<T> visited, List<T> sorted,Comparator<T> comparator)
    {
        if( !visited.contains(t) )
        {
            visited.add( t );

            Set<T> dependencies = _dependencies.get(t);
            if (dependencies!=null)
            {
                SortedSet<T> ordered_deps = new TreeSet<>(comparator);
                ordered_deps.addAll(dependencies);
                for (T d:ordered_deps)
                    visit(d,visited,sorted,comparator);
            }
            sorted.add(t);
        }
        else if (!sorted.contains(t))
            throw new IllegalStateException("cyclic");
    }
    
    private static class InitialOrderComparitor<T> implements Comparator<T>
    {
        private final Map<T,Integer> _indexes = new HashMap<>();
        InitialOrderComparitor(T[] initial)
        {
            int i=0;
            for (T t : initial)
                _indexes.put(t,i++);
        }
        
        InitialOrderComparitor(Collection<T> initial)
        {
            int i=0;
            for (T t : initial)
                _indexes.put(t,i++);
        }
        
        @Override
        public int compare(T o1, T o2)
        {
            Integer i1=_indexes.get(o1);
            Integer i2=_indexes.get(o2);
            if (i1==null || i2==null || i1.equals(o2))
                return 0;
            if (i1<i2)
                return -1;
            return 1;
        }
        
    }
}
