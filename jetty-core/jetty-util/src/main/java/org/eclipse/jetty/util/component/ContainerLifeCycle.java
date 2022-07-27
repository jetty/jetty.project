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

package org.eclipse.jetty.util.component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EventListener;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A ContainerLifeCycle is an {@link LifeCycle} implementation for a collection of contained beans.
 * <p>
 * Beans can be added to the ContainerLifeCycle either as managed beans or as unmanaged beans.
 * A managed bean is started, stopped and destroyed with the aggregate.
 * An unmanaged bean is associated with the aggregate for the purposes of {@link #dump()}, but its
 * lifecycle must be managed externally.
 * <p>
 * When a {@link LifeCycle} bean is added without a managed state being specified the state is
 * determined heuristically:
 * <ul>
 * <li>If the added bean is running, it will be added as an unmanaged bean.</li>
 * <li>If the added bean is !running and the container is !running, it will be added as an AUTO bean (see below).</li>
 * <li>If the added bean is !running and the container is starting, it will be added as a managed bean
 * and will be started (this handles the frequent case of new beans added during calls to doStart).</li>
 * <li>If the added bean is !running and the container is started, it will be added as an unmanaged bean.</li>
 * </ul>
 * When the container is started, then all contained managed beans will also be started.
 * Any contained AUTO beans will be check for their status and if already started will be switched unmanaged beans,
 * else they will be started and switched to managed beans.
 * Beans added after a container is started are not started and their state needs to be explicitly managed.
 * <p>
 * When stopping the container, a contained bean will be stopped by this aggregate only if it
 * is started by this aggregate.
 * <p>
 * The methods {@link #addBean(Object, boolean)}, {@link #manage(Object)} and {@link #unmanage(Object)} can be used to
 * explicitly control the life cycle relationship.
 * <p>
 * If adding a bean that is shared between multiple {@link ContainerLifeCycle} instances, then it should be started
 * before being added, so it is unmanaged, or the API must be used to explicitly set it as unmanaged.
 * <p>
 * All {@link EventListener}s added via {@link #addEventListener(EventListener)} are also added as beans and all beans
 * added via an {@link #addBean(Object)} method that are also {@link EventListener}s are added as listeners via a
 * call to {@link #addEventListener(EventListener)}.
 * <p>
 * This class also provides utility methods to dump deep structures of objects.
 * In the dump, the following symbols are used to indicate the type of contained object:
 * <pre>
 * SomeContainerLifeCycleInstance
 *   +- contained POJO instance
 *   += contained MANAGED object, started and stopped with this instance
 *   +~ referenced UNMANAGED object, with separate lifecycle
 *   +? referenced AUTO object that could become MANAGED or UNMANAGED.
 * </pre>
 */
@ManagedObject("Implementation of Container and LifeCycle")
public class ContainerLifeCycle extends AbstractLifeCycle implements Container, Destroyable, Dumpable.DumpableContainer
{
    private static final Logger LOG = LoggerFactory.getLogger(ContainerLifeCycle.class);
    private final List<Bean> _beans = new CopyOnWriteArrayList<>();
    private final List<Container.Listener> _listeners = new CopyOnWriteArrayList<>();
    private boolean _doStarted;
    private boolean _destroyed;

    /**
     * Starts the managed lifecycle beans in the order they were added.
     */
    @Override
    protected void doStart() throws Exception
    {
        if (_destroyed)
            throw new IllegalStateException("Destroyed container cannot be restarted");

        // indicate that we are started, so that addBean will start other beans added.
        _doStarted = true;

        // start our managed and auto beans
        try
        {
            for (Bean b : _beans)
            {
                if (!isStarting())
                    break;
                if (b._bean instanceof LifeCycle)
                {
                    LifeCycle l = (LifeCycle)b._bean;
                    switch (b._managed)
                    {
                        case MANAGED:
                            if (l.isStopped() || l.isFailed())
                                start(l);
                            break;

                        case AUTO:
                            if (l.isStopped())
                            {
                                manage(b);
                                start(l);
                            }
                            else
                            {
                                unmanage(b);
                            }
                            break;

                        default:
                            break;
                    }
                }
            }
        }
        catch (Throwable th)
        {
            // on failure, stop any managed components that have been started
            List<Bean> reverse = new ArrayList<>(_beans);
            Collections.reverse(reverse);
            for (Bean b : reverse)
            {
                if (b._bean instanceof LifeCycle && b._managed == Managed.MANAGED)
                {
                    LifeCycle l = (LifeCycle)b._bean;
                    if (l.isRunning())
                    {
                        try
                        {
                            stop(l);
                        }
                        catch (Throwable th2)
                        {
                            if (th2 != th)
                                th.addSuppressed(th2);
                        }
                    }
                }
            }
            throw th;
        }
    }

    /**
     * Starts the given lifecycle.
     *
     * @param l the lifecycle to start
     * @throws Exception if unable to start lifecycle
     */
    protected void start(LifeCycle l) throws Exception
    {
        l.start();
    }

    /**
     * Stops the given lifecycle.
     *
     * @param l the lifecycle to stop
     * @throws Exception if unable to stop the lifecycle
     */
    protected void stop(LifeCycle l) throws Exception
    {
        l.stop();
    }

    /**
     * Stops the managed lifecycle beans in the reverse order they were added.
     */
    @Override
    protected void doStop() throws Exception
    {
        _doStarted = false;
        super.doStop();
        List<Bean> reverse = new ArrayList<>(_beans);
        Collections.reverse(reverse);
        Throwable multiException = null;
        for (Bean b : reverse)
        {
            if (!isStopping())
                break;
            if (b._managed == Managed.MANAGED && b._bean instanceof LifeCycle)
            {
                LifeCycle l = (LifeCycle)b._bean;
                try
                {
                    stop(l);
                }
                catch (Throwable th)
                {
                    multiException = ExceptionUtil.combine(multiException, th);
                }
            }
        }
        ExceptionUtil.ifExceptionThrow(multiException);
    }

    /**
     * Destroys the managed Destroyable beans in the reverse order they were added.
     */
    @Override
    public void destroy()
    {
        _destroyed = true;
        List<Bean> reverse = new ArrayList<>(_beans);
        Collections.reverse(reverse);
        for (Bean b : reverse)
        {
            if (b._bean instanceof Destroyable && (b._managed == Managed.MANAGED || b._managed == Managed.POJO))
            {
                Destroyable d = (Destroyable)b._bean;
                try
                {
                    d.destroy();
                }
                catch (Throwable th)
                {
                    LOG.warn("Unable to destroy", th);
                }
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
        {
            if (b._bean == bean)
                return true;
        }
        return false;
    }

    /**
     * @param bean the bean to test
     * @return whether this aggregate contains and manages the bean
     */
    @Override
    public boolean isManaged(Object bean)
    {
        for (Bean b : _beans)
        {
            if (b._bean == bean)
                return b.isManaged();
        }
        return false;
    }

    /**
     * @param bean the bean to test
     * @return whether this aggregate contains the bean in auto state
     */
    public boolean isAuto(Object bean)
    {
        for (Bean b : _beans)
        {
            if (b._bean == bean)
                return b._managed == Managed.AUTO;
        }
        return false;
    }

    /**
     * @param bean the bean to test
     * @return whether this aggregate contains the bean in auto state
     */
    public boolean isUnmanaged(Object bean)
    {
        for (Bean b : _beans)
        {
            if (b._bean == bean)
                return b._managed == Managed.UNMANAGED;
        }
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
    @Override
    public boolean addBean(Object o)
    {
        if (o instanceof LifeCycle)
        {
            LifeCycle l = (LifeCycle)o;
            return addBean(o, l.isRunning() ? Managed.UNMANAGED : Managed.AUTO);
        }

        return addBean(o, Managed.POJO);
    }

    /**
     * Adds the given bean, explicitly managing it or not.
     *
     * @param o The bean object to add
     * @param managed whether to managed the lifecycle of the bean
     * @return true if the bean was added, false if it was already present
     */
    @Override
    public boolean addBean(Object o, boolean managed)
    {
        if (o instanceof LifeCycle)
            return addBean(o, managed ? Managed.MANAGED : Managed.UNMANAGED);
        return addBean(o, managed ? Managed.POJO : Managed.UNMANAGED);
    }

    private boolean addBean(Object o, Managed managed)
    {
        if (o == null || contains(o))
            return false;

        Bean newBean = new Bean(o);

        // Add the bean
        _beans.add(newBean);

        // Tell any existing listeners about the new bean
        for (Container.Listener l : _listeners)
            l.beanAdded(this, o);

        // if the bean is an EventListener, then add it. Because we have already added it as a bean above, then
        // addBean will not be called back.
        if (o instanceof EventListener)
            addEventListener((EventListener)o);

        try
        {
            switch (managed)
            {
                case UNMANAGED:
                    unmanage(newBean);
                    break;

                case MANAGED:
                    manage(newBean);

                    if (isStarting() && _doStarted)
                    {
                        LifeCycle l = (LifeCycle)o;
                        if (!l.isRunning())
                            start(l);
                    }
                    break;

                case AUTO:
                    if (o instanceof LifeCycle)
                    {
                        LifeCycle l = (LifeCycle)o;
                        if (isStarting())
                        {
                            if (l.isRunning())
                                unmanage(newBean);
                            else if (_doStarted)
                            {
                                manage(newBean);
                                start(l);
                            }
                            else
                                newBean._managed = Managed.AUTO;
                        }
                        else if (isStarted())
                            unmanage(newBean);
                        else
                            newBean._managed = Managed.AUTO;
                    }
                    else
                        newBean._managed = Managed.POJO;
                    break;

                case POJO:
                    newBean._managed = Managed.POJO;
                    break;

                default:
                    throw new IllegalStateException(managed.toString());
            }
        }
        catch (RuntimeException | Error e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{} added {}", this, newBean);

        return true;
    }

    /**
     * Adds a managed lifecycle.
     * <p>This is a convenience method that uses addBean(lifecycle,true)
     * and then ensures that the added bean is started iff this container
     * is running.  Exception from nested calls to start are caught and
     * wrapped as RuntimeExceptions
     *
     * @param lifecycle the managed lifecycle to add
     */
    public void addManaged(LifeCycle lifecycle)
    {
        addBean(lifecycle, true);
        try
        {
            if (isRunning() && !lifecycle.isRunning())
                start(lifecycle);
        }
        catch (RuntimeException | Error e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean addEventListener(EventListener listener)
    {
        // Has it already been added as a listener?
        if (super.addEventListener(listener))
        {
            // If it is not yet a bean,
            if (!contains(listener))
                // add it as a bean, we will be called back to add it as an event listener, but it will have
                // already been added, so we will not enter this branch.
                addBean(listener);

            if (listener instanceof Container.Listener)
            {
                Container.Listener cl = (Container.Listener)listener;
                _listeners.add(cl);

                // tell it about existing beans
                for (Bean b : _beans)
                {
                    cl.beanAdded(this, b._bean);

                    // handle inheritance
                    if (listener instanceof InheritedListener && b.isManaged() && b._bean instanceof Container)
                    {
                        if (b._bean instanceof ContainerLifeCycle)
                            Container.addBean(b._bean, listener, false);
                        else
                            Container.addBean(b._bean, listener);
                    }
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean removeEventListener(EventListener listener)
    {
        if (super.removeEventListener(listener))
        {
            removeBean(listener);
            if (listener instanceof Container.Listener && _listeners.remove(listener))
            {
                Container.Listener cl = (Container.Listener)listener;
                // remove existing beans
                for (Bean b : _beans)
                {
                    cl.beanRemoved(this, b._bean);

                    if (listener instanceof InheritedListener && b.isManaged())
                        Container.removeBean(b._bean, listener);
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Manages a bean already contained by this aggregate, so that it is started/stopped/destroyed with this
     * aggregate.
     *
     * @param bean The bean to manage (must already have been added).
     */
    @Override
    public void manage(Object bean)
    {
        for (Bean b : _beans)
        {
            if (b._bean == bean)
            {
                manage(b);
                return;
            }
        }
        throw new IllegalArgumentException("Unknown bean " + bean);
    }

    private void manage(Bean bean)
    {
        if (bean._managed != Managed.MANAGED)
        {
            bean._managed = Managed.MANAGED;

            if (bean._bean instanceof Container)
            {
                for (Container.Listener l : _listeners)
                {
                    if (l instanceof InheritedListener)
                    {
                        if (bean._bean instanceof ContainerLifeCycle)
                            Container.addBean(bean._bean, l, false);
                        else
                            Container.addBean(bean._bean, l);
                    }
                }
            }
        }
    }

    /**
     * Unmanages a bean already contained by this aggregate, so that it is not started/stopped/destroyed with this
     * aggregate.
     *
     * @param bean The bean to unmanage (must already have been added).
     */
    @Override
    public void unmanage(Object bean)
    {
        for (Bean b : _beans)
        {
            if (b._bean == bean)
            {
                unmanage(b);
                return;
            }
        }
        throw new IllegalArgumentException("Unknown bean " + bean);
    }

    private void unmanage(Bean bean)
    {
        if (bean._managed != Managed.UNMANAGED)
        {
            if (bean._managed == Managed.MANAGED && bean._bean instanceof Container)
            {
                for (Container.Listener l : _listeners)
                {
                    if (l instanceof InheritedListener)
                        Container.removeBean(bean._bean, l);
                }
            }
            bean._managed = Managed.UNMANAGED;
        }
    }

    public void setBeans(Collection<Object> beans)
    {
        for (Object bean : beans)
        {
            addBean(bean);
        }
    }

    @Override
    public Collection<Object> getBeans()
    {
        return getBeans(Object.class);
    }

    @Override
    public <T> Collection<T> getBeans(Class<T> clazz)
    {
        ArrayList<T> beans = null;
        for (Bean b : _beans)
        {
            if (clazz.isInstance(b._bean))
            {
                if (beans == null)
                    beans = new ArrayList<>();
                beans.add(clazz.cast(b._bean));
            }
        }
        return beans == null ? Collections.emptyList() : beans;
    }

    @Override
    public <T> T getBean(Class<T> clazz)
    {
        for (Bean b : _beans)
        {
            if (clazz.isInstance(b._bean))
                return clazz.cast(b._bean);
        }
        return null;
    }

    private Bean getBean(Object o)
    {
        for (Bean b : _beans)
        {
            if (b._bean == o)
                return b;
        }
        return null;
    }

    /**
     * Removes all bean
     */
    public void removeBeans()
    {
        ArrayList<Bean> beans = new ArrayList<>(_beans);
        for (Bean b : beans)
        {
            remove(b);
        }
    }

    @Override
    public boolean removeBean(Object o)
    {
        Bean b = getBean(o);
        return b != null && remove(b);
    }

    private boolean remove(Bean bean)
    {
        if (_beans.remove(bean))
        {
            final boolean wasManaged = bean.isManaged();

            unmanage(bean);

            for (Container.Listener l : _listeners)
            {
                l.beanRemoved(this, bean._bean);
            }

            // Remove event listeners, checking list here to avoid calling extended removeEventListener if already removed.
            if (bean._bean instanceof EventListener && getEventListeners().contains(bean._bean))
                removeEventListener((EventListener)bean._bean);

            // stop managed beans
            if (wasManaged && bean._bean instanceof LifeCycle)
            {
                try
                {
                    stop((LifeCycle)bean._bean);
                }
                catch (RuntimeException | Error e)
                {
                    throw e;
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Dumps to {@link System#err}.
     *
     * @see #dump()
     */
    @ManagedOperation("Dump the object to stderr")
    public void dumpStdErr()
    {
        try
        {
            dump(System.err, "");
            System.err.println(Dumpable.KEY);
        }
        catch (IOException e)
        {
            LOG.warn("Unable to dump", e);
        }
    }

    @Override
    @ManagedOperation("Dump the object to a string")
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent);
    }

    /**
     * Dump this object to an Appendable with no indent.
     *
     * @param out The appendable to dump to.
     * @throws IOException May be thrown by the Appendable
     */
    public void dump(Appendable out) throws IOException
    {
        dump(out, "");
    }

    /**
     * Dump this object, it's contained beans and additional items to an Appendable
     *
     * @param out The appendable to dump to
     * @param indent The indent to apply after any new lines
     * @param items Additional items to be dumped as contained.
     * @throws IOException May be thrown by the Appendable
     */
    protected void dumpObjects(Appendable out, String indent, Object... items) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this, items);
    }

    enum Managed
    {
        POJO, MANAGED, UNMANAGED, AUTO
    }

    private static class Bean
    {
        private final Object _bean;
        private volatile Managed _managed = Managed.POJO;

        private Bean(Object b)
        {
            if (b == null)
                throw new NullPointerException();
            _bean = b;
        }

        public boolean isManaged()
        {
            return _managed == Managed.MANAGED;
        }

        public boolean isManageable()
        {
            switch (_managed)
            {
                case MANAGED:
                    return true;
                case AUTO:
                    return _bean instanceof LifeCycle && ((LifeCycle)_bean).isStopped();
                default:
                    return false;
            }
        }

        @Override
        public String toString()
        {
            return String.format("{%s,%s}", _bean, _managed);
        }
    }

    public void updateBean(Object oldBean, final Object newBean)
    {
        if (newBean != oldBean)
        {
            if (oldBean != null)
                removeBean(oldBean);
            if (newBean != null)
                addBean(newBean);
        }
    }

    public void updateBean(Object oldBean, final Object newBean, boolean managed)
    {
        if (newBean != oldBean)
        {
            if (oldBean != null)
                removeBean(oldBean);
            if (newBean != null)
                addBean(newBean, managed);
        }
    }

    public void updateBeans(Object[] oldBeans, final Object[] newBeans)
    {
        updateBeans(
            oldBeans == null ? Collections.emptyList() : Arrays.asList(oldBeans),
            newBeans == null ? Collections.emptyList() : Arrays.asList(newBeans));
    }

    public void updateBeans(final Collection<?> oldBeans, final Collection<?> newBeans)
    {
        Objects.requireNonNull(oldBeans);
        Objects.requireNonNull(newBeans);

        // remove oldChildren not in newChildren
        for (Object o : oldBeans)
        {
            if (!newBeans.contains(o))
                removeBean(o);
        }

        // add new beans not in old
        for (Object n : newBeans)
        {
            if (!oldBeans.contains(n))
                addBean(n);
        }
    }

    @Override
    public <T> Collection<T> getContainedBeans(Class<T> clazz)
    {
        Set<T> beans = new HashSet<>();
        getContainedBeans(clazz, beans);
        return beans;
    }

    protected <T> void getContainedBeans(Class<T> clazz, Collection<T> beans)
    {
        beans.addAll(getBeans(clazz));
        for (Container c : getBeans(Container.class))
        {
            Bean bean = getBean(c);
            if (bean != null && bean.isManageable())
            {
                if (c instanceof ContainerLifeCycle)
                    ((ContainerLifeCycle)c).getContainedBeans(clazz, beans);
                else
                    beans.addAll(c.getContainedBeans(clazz));
            }
        }
    }
}
