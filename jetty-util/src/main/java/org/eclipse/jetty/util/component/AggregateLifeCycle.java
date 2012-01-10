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
 * Beans can be added the AggregateLifeCycle either as joined beans, as disjoint beans.  A joined bean is started, stopped and destroyed with the aggregate.  
 * A disjointed bean is associated with the aggregate for the purposes of {@link #dump()}, but it's lifecycle must be managed externally.
 * <p>
 * When a bean is added, if it is a {@link LifeCycle} and it is already started, then it is assumed to be a disjoined bean.  
 * Otherwise the methods {@link #addBean(LifeCycle, boolean)}, {@link #join(LifeCycle)} and {@link #disjoin(LifeCycle)} can be used to 
 * explicitly control the life cycle relationship.
 * <p>
 */
public class AggregateLifeCycle extends AbstractLifeCycle implements Destroyable, Dumpable
{
    private static final Logger LOG = Log.getLogger(AggregateLifeCycle.class);
    private final List<Bean> _beans=new CopyOnWriteArrayList<Bean>();

    private class Bean
    {
        Bean(Object b) 
        {
            _bean=b;
        }
        final Object _bean;
        volatile boolean _joined=true;
    }

    /* ------------------------------------------------------------ */
    /**
     * Start the joined lifecycle beans in the order they were added.
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        for (Bean b:_beans)
        {
            if (b._joined && b._bean instanceof LifeCycle)
            {
                LifeCycle l=(LifeCycle)b._bean;
                if (!l.isRunning())
                    l.start();
            }
        }
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
        super.doStop();
        List<Bean> reverse = new ArrayList<Bean>(_beans);
        Collections.reverse(reverse);
        for (Bean b:reverse)
        {
            if (b._joined && b._bean instanceof LifeCycle)
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
            if (b._bean instanceof Destroyable && b._joined)
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
    public boolean isJoined(Object bean)
    {
        for (Bean b:_beans)
            if (b._bean==bean)
                return b._joined;
        return false;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Add an associated bean.
     * If the bean is a {@link LifeCycle}, it is added as neither joined or disjoint and 
     * that status will be determined when the Aggregate bean is started.
     * @param o the bean object to add
     * @return true if the bean was added or false if it has already been added.
     */
    public boolean addBean(Object o)
    {
        if (contains(o))
            return false;
        
        Bean b = new Bean(o);
        _beans.add(b);
        
        // extra LifeCycle handling
        if (o instanceof LifeCycle)
        {
            LifeCycle l=(LifeCycle)o;

            // If it is  running, then assume it is disjoint
            if (l.isRunning())
                b._joined=false;

            // else if we are running, then start the bean
            else if (isRunning())
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
    /** Add an associated lifecycle.
     * @param o The lifecycle to add
     * @param joined True if the LifeCycle is to be joined, otherwise it will be disjoint.
     * @return
     */
    public boolean addBean(Object o, boolean joined)
    {
        if (contains(o))
            return false;
        
        Bean b = new Bean(o);
        b._joined=joined;
        _beans.add(b);
        
        if (o instanceof LifeCycle)
        {
            LifeCycle l=(LifeCycle)o;

            if (joined && isStarted())
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
     * Join a  bean to this aggregate, so that it is started/stopped/destroyed with the 
     * aggregate lifecycle.  
     * @param bean The bean to join (must already have been added).
     */
    public void join(Object bean)
    {    
        for (Bean b :_beans)
        {
            if (b._bean==bean)
            {
                b._joined=true;
                return;
            }
        }
        throw new IllegalArgumentException();
    }

    /* ------------------------------------------------------------ */
    /**
     * Disjoin a bean to this aggregate, so that it is not started/stopped/destroyed with the 
     * aggregate lifecycle.  
     * @param bean The bean to join (must already have been added).
     */
    public void disjoin(Object bean)
    {
        for (Bean b :_beans)
        {
            if (b._bean==bean)
            {
                b._joined=false;
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
        out.append(String.valueOf(this)).append("\n");
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
            
            if (b._joined)
            {
                out.append(indent).append(" +- ");
                if (b._bean instanceof Dumpable)
                    ((Dumpable)b._bean).dump(out,indent+(i==size?"    ":" |  "));
                else
                    out.append(String.valueOf(b._bean)).append("\n");
            }
            else
            {
                out.append(indent).append(" +~ ");
                out.append(String.valueOf(b._bean)).append("\n");
            }

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
                    out.append(String.valueOf(o)).append("\n");
            }
            
            if (i!=size)
                out.append(indent).append(" |\n");
                
        }
    }
    
    
    
}
