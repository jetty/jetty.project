//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * An AggregateLifeCycle is an {@link LifeCycle} implementation for a collection of contained beans.
 * <p/>
 * Beans can be added the AggregateLifeCycle either as managed beans or as unmanaged beans.  A managed bean is started, stopped and destroyed with the aggregate.
 * An unmanaged bean is associated with the aggregate for the purposes of {@link #dump()}, but it's lifecycle must be managed externally.
 * <p/>
 * When a bean is added, if it is a {@link LifeCycle} and it is already started, then it is assumed to be an unmanaged bean.
 * Otherwise the methods {@link #addBean(Object, boolean)}, {@link #manage(Object)} and {@link #unmanage(Object)} can be used to
 * explicitly control the life cycle relationship.
 * <p/>
 * If adding a bean that is shared between multiple {@link AggregateLifeCycle} instances, then it should be started before being added, so it is unmanaged, or
 * the API must be used to explicitly set it as unmanaged.
 * <p/>
 */
public class AggregateLifeCycle extends AbstractLifeCycle implements Destroyable, Dumpable
{
    private static final Logger LOG = Log.getLogger(AggregateLifeCycle.class);
    private final List<Bean> _beans = new CopyOnWriteArrayList<>();
    private boolean _started = false;

    private class Bean
    {
        private final Object _bean;
        private volatile boolean _managed = true;

        private Bean(Object b)
        {
            _bean = b;
        }

        public String toString()
        {
            return String.format("{%s,%b}", _bean, _managed);
        }
    }

    /**
     * Starts the managed lifecycle beans in the order they were added.
     */
    @Override
    protected void doStart() throws Exception
    {
        for (Bean b : _beans)
        {
            if (b._bean instanceof LifeCycle)
            {
                LifeCycle l = (LifeCycle)b._bean;
                if (!l.isRunning())
                {
                    if (b._managed)
                        l.start();
                }
            }
            if (b._managed && b._bean instanceof LifeCycle)
            {
                LifeCycle l = (LifeCycle)b._bean;
                if (!l.isRunning())
                    l.start();
            }
        }
        // indicate that we are started, so that addBean will start other beans added.
        _started = true;
        super.doStart();
    }

    /**
     * Stops the managed lifecycle beans in the reverse order they were added.
     */
    @Override
    protected void doStop() throws Exception
    {
        _started = false;
        super.doStop();
        List<Bean> reverse = new ArrayList<>(_beans);
        Collections.reverse(reverse);
        for (Bean b : reverse)
        {
            if (b._managed && b._bean instanceof LifeCycle)
            {
                LifeCycle l = (LifeCycle)b._bean;
                if (l.isRunning())
                    l.stop();
            }
        }
    }

    /**
     * Destroys the managed Destroyable beans in the reverse order they were added.
     */
    public void destroy()
    {
        List<Bean> reverse = new ArrayList<>(_beans);
        Collections.reverse(reverse);
        for (Bean b : reverse)
        {
            if (b._bean instanceof Destroyable && b._managed)
            {
                Destroyable d = (Destroyable)b._bean;
                d.destroy();
            }
        }
        _beans.clear();
    }



    /**
     * @param bean the bean to test
     * @return whether this aggregate contains the bean
     */
    public boolean contains(Object bean)
    {
        for (Bean b : _beans)
            if (b._bean == bean)
                return true;
        return false;
    }

    /**
     * @param bean the bean to test
     * @return whether this aggregate contains and manages the bean
     */
    public boolean isManaged(Object bean)
    {
        for (Bean b : _beans)
            if (b._bean == bean)
                return b._managed;
        return false;
    }

    /**
     * Adds the given bean, detecting whether to manage it or not.
     * If the bean is a {@link LifeCycle}, then it will be managed if it is not
     * already started and not managed if it is already started.
     * The {@link #addBean(Object, boolean)}
     * method should be used if this is not correct, or the {@link #manage(Object)} and {@link #unmanage(Object)}
     * methods may be used after an add to change the status.
     *
     * @param o the bean object to add
     * @return true if the bean was added, false if it was already present
     */
    public boolean addBean(Object o)
    {
        // beans are joined unless they are started lifecycles
        return addBean(o, !((o instanceof LifeCycle) && ((LifeCycle)o).isStarted()));
    }

    /**
     * Adds the given bean, explicitly managing it or not.
     *
     * @param o       The bean object to add
     * @param managed whether to managed the lifecycle of the bean
     * @return true if the bean was added, false if it was already present
     */
    public boolean addBean(Object o, boolean managed)
    {
        if (contains(o))
            return false;

        Bean b = new Bean(o);
        b._managed = managed;
        _beans.add(b);

        if (o instanceof LifeCycle)
        {
            LifeCycle l = (LifeCycle)o;

            // Start the bean if we are started
            if (managed && _started)
            {
                try
                {
                    l.start();
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }
        }
        return true;
    }

    /**
     * Manages a bean already contained by this aggregate, so that it is started/stopped/destroyed with this
     * aggregate.
     *
     * @param bean The bean to manage (must already have been added).
     */
    public void manage(Object bean)
    {
        for (Bean b : _beans)
        {
            if (b._bean == bean)
            {
                b._managed = true;
                return;
            }
        }
        throw new IllegalArgumentException("Unknown bean " + bean);
    }

    /**
     * Unmanages a bean already contained by this aggregate, so that it is not started/stopped/destroyed with this
     * aggregate.
     *
     * @param bean The bean to unmanage (must already have been added).
     */
    public void unmanage(Object bean)
    {
        for (Bean b : _beans)
        {
            if (b._bean == bean)
            {
                b._managed = false;
                return;
            }
        }
        throw new IllegalArgumentException("Unknown bean " + bean);
    }

    /**
     * @return the list of beans known to this aggregate
     * @see #getBean(Class)
     */
    public Collection<Object> getBeans()
    {
        return getBeans(Object.class);
    }

    /**
     * @param clazz the class of the beans
     * @return the list of beans of the given class (or subclass)
     * @see #getBeans()
     */
    public <T> List<T> getBeans(Class<T> clazz)
    {
        ArrayList<T> beans = new ArrayList<>();
        for (Bean b : _beans)
        {
            if (clazz.isInstance(b._bean))
                beans.add(clazz.cast(b._bean));
        }
        return beans;
    }

    /**
     * @param clazz the class of the bean
     * @return the first bean of a specific class (or subclass), or null if no such bean exist
     */
    public <T> T getBean(Class<T> clazz)
    {
        for (Bean b : _beans)
        {
            if (clazz.isInstance(b._bean))
                return clazz.cast(b._bean);
        }
        return null;
    }

    /**
     * Removes all bean, without performing any lifecycle
     * @see #destroy()
     */
    public void removeBeans()
    {
        _beans.clear();
    }

    /**
     * Removes the given bean.
     * @return whether the bean was removed
     * @see #removeBeans()
     */
    public boolean removeBean(Object o)
    {
        for (Bean b : _beans)
        {
            if (b._bean == o)
            {
                _beans.remove(b);
                return true;
            }
        }
        return false;
    }

    @Override
    public void setStopTimeout(long stopTimeout)
    {
        super.setStopTimeout(stopTimeout);
        for (Bean bean : _beans)
        {
            Object component = bean._bean;
            if (component instanceof AbstractLifeCycle)
            {
                ((AbstractLifeCycle)component).setStopTimeout(stopTimeout);
            }
        }
    }

    /**
     * Dumps to {@link System#err}.
     * @see #dump()
     */
    public void dumpStdErr()
    {
        try
        {
            dump(System.err, "");
        }
        catch (IOException e)
        {
            LOG.warn(e);
        }
    }

    public String dump()
    {
        return dump(this);
    }

    public static String dump(Dumpable dumpable)
    {
        StringBuilder b = new StringBuilder();
        try
        {
            dumpable.dump(b, "");
        }
        catch (IOException e)
        {
            LOG.warn(e);
        }
        return b.toString();
    }

    public void dump(Appendable out) throws IOException
    {
        dump(out, "");
    }

    protected void dumpThis(Appendable out) throws IOException
    {
        out.append(String.valueOf(this)).append(" - ").append(getState()).append("\n");
    }

    public static void dumpObject(Appendable out, Object o) throws IOException
    {
        try
        {
            if (o instanceof LifeCycle)
                out.append(String.valueOf(o)).append(" - ").append((AbstractLifeCycle.getState((LifeCycle)o))).append("\n");
            else
                out.append(String.valueOf(o)).append("\n");
        }
        catch (Throwable th)
        {
            out.append(" => ").append(th.toString()).append('\n');
        }
    }

    public void dump(Appendable out, String indent) throws IOException
    {
        dumpThis(out);
        int size = _beans.size();
        if (size == 0)
            return;
        int i = 0;
        for (Bean b : _beans)
        {
            i++;

            if (b._managed)
            {
                out.append(indent).append(" +- ");
                if (b._bean instanceof Dumpable)
                    ((Dumpable)b._bean).dump(out, indent + (i == size ? "    " : " |  "));
                else
                    dumpObject(out, b._bean);
            }
            else
            {
                out.append(indent).append(" +~ ");
                dumpObject(out, b._bean);
            }
        }

        if (i != size)
            out.append(indent).append(" |\n");
    }

    public static void dump(Appendable out, String indent, Collection<?>... collections) throws IOException
    {
        if (collections.length == 0)
            return;
        int size = 0;
        for (Collection<?> c : collections)
            size += c.size();
        if (size == 0)
            return;

        int i = 0;
        for (Collection<?> c : collections)
        {
            for (Object o : c)
            {
                i++;
                out.append(indent).append(" +- ");

                if (o instanceof Dumpable)
                    ((Dumpable)o).dump(out, indent + (i == size ? "    " : " |  "));
                else
                    dumpObject(out, o);
            }

            if (i != size)
                out.append(indent).append(" |\n");
        }
    }
}
