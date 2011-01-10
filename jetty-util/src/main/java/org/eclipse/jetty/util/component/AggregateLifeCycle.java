package org.eclipse.jetty.util.component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;


import org.eclipse.jetty.util.log.Log;

/**
 * An AggregateLifeCycle is an AbstractLifeCycle with a collection of dependent beans.
 * <p>
 * Dependent beans are started and stopped with the {@link LifeCycle} and if they are destroyed if they are also {@link Destroyable}.
 *
 */
public class AggregateLifeCycle extends AbstractLifeCycle implements Destroyable, Dumpable
{
    private final Queue<Object> _dependentBeans=new ConcurrentLinkedQueue<Object>();

    public void destroy()
    {
        for (Object o : _dependentBeans)
        {
            if (o instanceof Destroyable)
            {
                ((Destroyable)o).destroy();
            }
        }
        _dependentBeans.clear();
    }

    @Override
    protected void doStart() throws Exception
    {
        for (Object o:_dependentBeans)
        {
            if (o instanceof LifeCycle)
                ((LifeCycle)o).start();
        }
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        for (Object o:_dependentBeans)
        {
            if (o instanceof LifeCycle)
                ((LifeCycle)o).stop();
        }
    }


    /* ------------------------------------------------------------ */
    /**
     * Add an associated bean.
     * The bean will be added to this LifeCycle and if it is also a 
     * {@link LifeCycle} instance, it will be 
     * started/stopped. Any beans that are also 
     * {@link Destroyable}, will be destroyed with the server.
     * @param o the bean object to add
     */
    public boolean addBean(Object o)
    {
        if (o == null)
            return false;
        boolean added=false;
        if (!_dependentBeans.contains(o)) 
        {
            _dependentBeans.add(o);
            added=true;
        }
        
        try
        {
            if (isStarted() && o instanceof LifeCycle)
                ((LifeCycle)o).start();
        }
        catch (Exception e)
        {
            throw new RuntimeException (e);
        }
        return added;
    }

    /* ------------------------------------------------------------ */
    /** Get dependent beans 
     * @return List of beans.
     */
    public Collection<Object> getBeans()
    {
        return _dependentBeans;
    }
    
    /* ------------------------------------------------------------ */
    /** Get dependent beans of a specific class
     * @see #addBean(Object)
     * @param clazz
     * @return List of beans.
     */
    public <T> List<T> getBeans(Class<T> clazz)
    {
        ArrayList<T> beans = new ArrayList<T>();
        Iterator<?> iter = _dependentBeans.iterator();
        while (iter.hasNext())
        {
            Object o = iter.next();
            if (clazz.isInstance(o))
                beans.add((T)o);
        }
        return beans;
    }

    
    /* ------------------------------------------------------------ */
    /** Get dependent bean of a specific class.
     * If more than one bean of the type exist, the first is returned.
     * @see #addBean(Object)
     * @param clazz
     * @return bean or null
     */
    public <T> T getBean(Class<T> clazz)
    {
        Iterator<?> iter = _dependentBeans.iterator();
        T t=null;
        int count=0;
        while (iter.hasNext())
        {
            Object o = iter.next();
            if (clazz.isInstance(o))
            {
                count++;
                if (t==null)
                    t=(T)o;
            }
        }
        if (count>1)
            Log.debug("getBean({}) 1 of {}",clazz.getName(),count);
        
        return t;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Remove all associated bean.
     */
    public void removeBeans ()
    {
        _dependentBeans.clear();
    }

    /* ------------------------------------------------------------ */
    /**
     * Remove an associated bean.
     */
    public boolean removeBean (Object o)
    {
        if (o == null)
            return false;
        return _dependentBeans.remove(o);
    }

    /* ------------------------------------------------------------ */
    public void dumpStdErr()
    {
        try
        {
            dump(System.err,"");
        }
        catch (IOException e)
        {
            Log.warn(e);
        }
    }
    
    /* ------------------------------------------------------------ */
    public String dump()
    {
        return dump(this);
    }    
    
    /* ------------------------------------------------------------ */
    public static String dump(Dumpable dumpable)
    {
        StringBuilder b = new StringBuilder();
        try
        {
            dumpable.dump(b,"");
        }
        catch (IOException e)
        {
            Log.warn(e);
        }
        return b.toString();
    }    

    /* ------------------------------------------------------------ */
    public void dump(Appendable out) throws IOException
    {
        dump(out,"");
    }

    /* ------------------------------------------------------------ */
    protected void dumpThis(Appendable out) throws IOException
    {
        out.append(String.valueOf(this)).append("\n");
    }
    
    /* ------------------------------------------------------------ */
    public void dump(Appendable out,String indent) throws IOException
    {
        dumpThis(out);
        dump(out,indent,_dependentBeans);
    }
    
    /* ------------------------------------------------------------ */
    public static void dump(Appendable out,String indent,Collection<?>... collections) throws IOException
    {
        if (collections.length==0)
            return;
        int size=0;
        for (Collection<?> c : collections)
            size+=c.size();    
        if (size==0)
            return;

        int i=0;
        for (Collection<?> c : collections)
        {
            for (Object o : c)
            {
                i++;
                out.append(indent).append(" +- ");

                if (o instanceof Dumpable)
                    ((Dumpable)o).dump(out,indent+(i==size?"    ":" |  "));
                else
                    out.append(String.valueOf(o)).append("\n");
            }
            
            if (i!=size)
                out.append(indent).append(" |\n");
                
        }
    }
    
    
    
}
