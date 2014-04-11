//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * An AggregateLifeCycle is an {@link LifeCycle} implementation for a collection of contained beans.
 * <p>
 * Beans can be added the AggregateLifeCycle either as managed beans or as unmanaged beans.  A managed bean is started, stopped and destroyed with the aggregate.  
 * An unmanaged bean is associated with the aggregate for the purposes of {@link #dump()}, but it's lifecycle must be managed externally.
 * <p>
 * When a bean is added, if it is a {@link LifeCycle} and it is already started, then it is assumed to be an unmanaged bean.  
 * Otherwise the methods {@link #addBean(Object, boolean)}, {@link #manage(Object)} and {@link #unmanage(Object)} can be used to 
 * explicitly control the life cycle relationship.
 * <p>
 * If adding a bean that is shared between multiple {@link AggregateLifeCycle} instances, then it should be started before being added, so it is unmanaged, or 
 * the API must be used to explicitly set it as unmanaged.
 * <p>
 */
public class AggregateLifeCycle extends AbstractLifeCycle implements Destroyable, Dumpable
{
    private static final Logger LOG = Log.getLogger(AggregateLifeCycle.class);
    private final List<Bean> _beans=new CopyOnWriteArrayList<Bean>();
    private boolean _started=false;

    private class Bean
    {
        Bean(Object b) 
        {
            _bean=b;
        }
        final Object _bean;
        volatile boolean _managed=true;
        
        public String toString()
        {
            return "{"+_bean+","+_managed+"}";
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Start the managed lifecycle beans in the order they were added.
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        for (Bean b:_beans)
        {
            if (b._managed && b._bean instanceof LifeCycle)
            {
                LifeCycle l=(LifeCycle)b._bean;
                if (!l.isRunning())
                    l.start();
            }
        }
        // indicate that we are started, so that addBean will start other beans added.
        _started=true;
        super.doStart();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Stop the joined lifecycle beans in the reverse order they were added.
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStop() throws Exception
    {
        _started=false;
        super.doStop();
        List<Bean> reverse = new ArrayList<Bean>(_beans);
        Collections.reverse(reverse);
        for (Bean b:reverse)
        {
            if (b._managed && b._bean instanceof LifeCycle)
            {
                LifeCycle l=(LifeCycle)b._bean;
                if (l.isRunning())
                    l.stop();
            }
        }
    }


    /* ------------------------------------------------------------ */
    /**
     * Destroy the joined Destroyable beans in the reverse order they were added.
     * @see org.eclipse.jetty.util.component.Destroyable#destroy()
     */
    public void destroy()
    {
        List<Bean> reverse = new ArrayList<Bean>(_beans);
        Collections.reverse(reverse);
        for (Bean b:reverse)
        {
            if (b._bean instanceof Destroyable && b._managed)
            {
                Destroyable d=(Destroyable)b._bean;
                d.destroy();
            }
        }
        _beans.clear();
    }


    /* ------------------------------------------------------------ */
    /** Is the bean contained in the aggregate.
     * @param bean
     * @return True if the aggregate contains the bean
     */
    public boolean contains(Object bean)
    {
        for (Bean b:_beans)
            if (b._bean==bean)
                return true;
        return false;
    }
    
    /* ------------------------------------------------------------ */
    /** Is the bean joined to the aggregate.
     * @param bean
     * @return True if the aggregate contains the bean and it is joined
     */
    public boolean isManaged(Object bean)
    {
        for (Bean b:_beans)
            if (b._bean==bean)
                return b._managed;
        return false;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Add an associated bean.
     * If the bean is a {@link LifeCycle}, then it will be managed if it is not 
     * already started and umanaged if it is already started. The {@link #addBean(Object, boolean)}
     * method should be used if this is not correct, or the {@link #manage(Object)} and {@link #unmanage(Object)}
     * methods may be used after an add to change the status.
     * @param o the bean object to add
     * @return true if the bean was added or false if it has already been added.
     */
    public boolean addBean(Object o)
    {
        // beans are joined unless they are started lifecycles
        return addBean(o,!((o instanceof LifeCycle)&&((LifeCycle)o).isStarted()));
    }
    
    /* ------------------------------------------------------------ */
    /** Add an associated lifecycle.
     * @param o The lifecycle to add
     * @param managed True if the LifeCycle is to be joined, otherwise it will be disjoint.
     * @return true if bean was added, false if already present.
     */
    public boolean addBean(Object o, boolean managed)
    {
        if (contains(o))
            return false;
        
        Bean b = new Bean(o);
        b._managed=managed;
        _beans.add(b);
        
        if (o instanceof LifeCycle)
        {
            LifeCycle l=(LifeCycle)o;

            // Start the bean if we are started
            if (managed && _started)
            {
                try
                {
                    l.start();
                }
                catch(Exception e)
                {
                    throw new RuntimeException (e);
                }
            }
        }
        return true;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Manage a bean by this aggregate, so that it is started/stopped/destroyed with the 
     * aggregate lifecycle.  
     * @param bean The bean to manage (must already have been added).
     */
    public void manage(Object bean)
    {    
        for (Bean b :_beans)
        {
            if (b._bean==bean)
            {
                b._managed=true;
                return;
            }
        }
        throw new IllegalArgumentException();
    }

    /* ------------------------------------------------------------ */
    /**
     * Unmanage a bean by this aggregate, so that it is not started/stopped/destroyed with the 
     * aggregate lifecycle.  
     * @param bean The bean to manage (must already have been added).
     */
    public void unmanage(Object bean)
    {
        for (Bean b :_beans)
        {
            if (b._bean==bean)
            {
                b._managed=false;
                return;
            }
        }
        throw new IllegalArgumentException();
    }
    
    /* ------------------------------------------------------------ */
    /** Get dependent beans 
     * @return List of beans.
     */
    public Collection<Object> getBeans()
    {
        return getBeans(Object.class);
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
        for (Bean b:_beans)
        {
            if (clazz.isInstance(b._bean))
                beans.add((T)(b._bean));
        }
        return beans;
    }

    
    /* ------------------------------------------------------------ */
    /** Get dependent beans of a specific class.
     * If more than one bean of the type exist, the first is returned.
     * @see #addBean(Object)
     * @param clazz
     * @return bean or null
     */
    public <T> T getBean(Class<T> clazz)
    {
        for (Bean b:_beans)
        {
            if (clazz.isInstance(b._bean))
                return (T)b._bean;
        }
        
        return null;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Remove all associated bean.
     */
    public void removeBeans ()
    {
        _beans.clear();
    }

    /* ------------------------------------------------------------ */
    /**
     * Remove an associated bean.
     */
    public boolean removeBean (Object o)
    {
        Iterator<Bean> i = _beans.iterator();
        while(i.hasNext())
        {
            Bean b=i.next();
            if (b._bean==o)
            {
                _beans.remove(b);
                return true;
            }
        }
        return false;
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
            LOG.warn(e);
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
            LOG.warn(e);
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
        out.append(String.valueOf(this)).append(" - ").append(getState()).append("\n");
    }

    /* ------------------------------------------------------------ */
    public static void dumpObject(Appendable out,Object o) throws IOException
    {
        try
        {
            if (o instanceof LifeCycle)
                out.append(String.valueOf(o)).append(" - ").append((AbstractLifeCycle.getState((LifeCycle)o))).append("\n");
            else
                out.append(String.valueOf(o)).append("\n");
        }
        catch(Throwable th)
        {
            out.append(" => ").append(th.toString()).append('\n');
        }
    }
    
    /* ------------------------------------------------------------ */
    public void dump(Appendable out,String indent) throws IOException
    {
        dumpThis(out);
        int size=_beans.size();
        if (size==0)
            return;
        int i=0;
        for (Bean b : _beans)
        {
            i++;

            out.append(indent).append(" +- ");
            if (b._managed)
            {
                if (b._bean instanceof Dumpable)
                    ((Dumpable)b._bean).dump(out,indent+(i==size?"    ":" |  "));
                else 
                    dumpObject(out,b._bean);
            }
            else 
                dumpObject(out,b._bean);
        }

        if (i!=size)
            out.append(indent).append(" |\n");
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
                    dumpObject(out,o);
            }
            
            if (i!=size)
                out.append(indent).append(" |\n");          
        }
    }
}
