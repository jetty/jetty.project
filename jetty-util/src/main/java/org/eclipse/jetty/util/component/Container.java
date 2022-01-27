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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EventListener;
import java.util.List;

/**
 * A Container
 */
public interface Container
{
    /**
     * Add a bean.  If the bean is-a {@link EventListener}, then also do an implicit {@link #addEventListener(EventListener)}.
     *
     * @param o the bean object to add
     * @return true if the bean was added, false if it was already present
     */
    boolean addBean(Object o);

    /**
     * Adds the given bean, explicitly managing it or not.
     * If the bean is-a {@link EventListener}, then also do an implicit {@link #addEventListener(EventListener)}.
     * @param o The bean object to add
     * @param managed whether to managed the lifecycle of the bean
     * @return true if the bean was added, false if it was already present
     */
    boolean addBean(Object o, boolean managed);

    /**
     * @return the collection of beans known to this aggregate, in the order they were added.
     */
    Collection<Object> getBeans();

    /**
     * @param clazz the class of the beans
     * @param <T> the Bean type
     * @return a list of beans of the given class (or subclass), in the order they were added.
     * @see #getBeans()
     * @see #getContainedBeans(Class)
     */
    <T> Collection<T> getBeans(Class<T> clazz);

    /**
     * @param clazz the class of the beans
     * @param <T> the Bean type
     * @return a list of beans of the given class (or subclass), which may be cached/shared.
     * @see #getBeans()
     * @see #getContainedBeans(Class)
     */
    default <T> Collection<T> getCachedBeans(Class<T> clazz)
    {
        return getBeans(clazz);
    }

    /**
     * @param clazz the class of the bean
     * @param <T> the Bean type
     * @return the first bean (in order added) of a specific class (or subclass), or null if no such bean exist
     */
    <T> T getBean(Class<T> clazz);


    /**
     * Removes the given bean.
     * If the bean is-a {@link EventListener}, then also do an implicit {@link #removeEventListener(EventListener)}.
     *
     * @param o the bean to remove
     * @return whether the bean was removed
     */
    boolean removeBean(Object o);

    /**
     * Add an event listener.
     * EventListeners added by this method are also added as beans.
     * @param listener the listener to add
     * @return true if the listener was added
     * @see Container.Listener
     * @see LifeCycle.Listener
     * @see Container#addBean(Object)
     */
    boolean addEventListener(EventListener listener);

    /**
     * Remove an event listener.
     *
     * @param listener the listener to remove
     * @return true if the listener was removed
     * @see Container#removeBean(Object)
     */
    boolean removeEventListener(EventListener listener);

    /**
     * Unmanages a bean already contained by this aggregate, so that it is not started/stopped/destroyed with this
     * aggregate.
     *
     * @param bean The bean to unmanage (must already have been added).
     */
    void unmanage(Object bean);

    /**
     * Manages a bean already contained by this aggregate, so that it is started/stopped/destroyed with this
     * aggregate.
     *
     * @param bean The bean to manage (must already have been added).
     */
    void manage(Object bean);

    /**
     * Test if this container manages a bean
     *
     * @param bean the bean to test
     * @return whether this aggregate contains and manages the bean
     */
    boolean isManaged(Object bean);

    /**
     * @param clazz the class of the beans
     * @param <T> the Bean type
     * @return the list of beans of the given class from the entire Container hierarchy.
     *         The order is by depth first and then the order beans were added.
     */
    <T> Collection<T> getContainedBeans(Class<T> clazz);

    /**
     * Get the beans added to the container that are EventListeners.
     * This is essentially equivalent to <code>getBeans(EventListener.class);</code>,
     * except that: <ul>
     *     <li>The result may be precomputed, so it can be more efficient</li>
     *     <li>The result is ordered by the order added.</li>
     *     <li>The result is immutable.</li>
     * </ul>
     * @see #getBeans(Class)
     * @return An unmodifiable list of EventListener beans
     */
    default List<EventListener> getEventListeners()
    {
        return Collections.unmodifiableList(new ArrayList<>(getBeans(EventListener.class)));
    }

    /**
     * A utility method to add a bean to a container.
     * @param parent the parent container.
     * @param child the child bean.
     * @return true if the child was added as a bean, false if parent was not instance of {@link Container} or bean was already present.
     */
    static boolean addBean(Object parent, Object child)
    {
        if (parent instanceof Container)
            return ((Container)parent).addBean(child);
        return false;
    }

    /**
     * A utility method to add a bean to a container.
     * @param parent the parent container.
     * @param child the child bean.
     * @param managed whether to managed the lifecycle of the bean.
     * @return true if the child was added as a bean, false if parent was not instance of {@link Container} or bean was already present.
     */
    static boolean addBean(Object parent, Object child, boolean managed)
    {
        if (parent instanceof Container)
            return ((Container)parent).addBean(child, managed);
        return false;
    }

    /**
     * A utility method to remove a bean from a container.
     * @param parent the parent container.
     * @param child the child bean.
     * @return true if parent was an instance of {@link Container} and the bean was removed.
     */
    static boolean removeBean(Object parent, Object child)
    {
        if (parent instanceof Container)
            return ((Container)parent).removeBean(child);
        return false;
    }

    /**
     * A listener for Container events.
     * If an added bean implements this interface it will receive the events
     * for this container.
     */
    interface Listener extends EventListener
    {
        void beanAdded(Container parent, Object child);

        void beanRemoved(Container parent, Object child);
    }

    /**
     * Inherited Listener.
     * If an added bean implements this interface, then it will
     * be added to all contained beans that are themselves Containers
     */
    interface InheritedListener extends Listener
    {
    }
}
