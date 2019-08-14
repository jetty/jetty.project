//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
     * Add a bean.  If the bean is-a {@link Listener}, then also do an implicit {@link #addEventListener(EventListener)}.
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
     * @return the list of beans known to this aggregate
     * @see #getBean(Class)
     */
    Collection<Object> getBeans();

    /**
     * @param clazz the class of the beans
     * @param <T> the Bean type
     * @return a list of beans of the given class (or subclass)
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
     * @return the first bean of a specific class (or subclass), or null if no such bean exist
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
     *
     * @param listener the listener to add
     * @see Container.Listener
     * @see LifeCycle.Listener
     * @see Container#addBean(Object)
     */
    void addEventListener(EventListener listener);

    /**
     * Remove an event listener.
     *
     * @param listener the listener to remove
     * @see Container#removeBean(Object)
     */
    void removeEventListener(EventListener listener);

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
     * @return the list of beans of the given class from the entire Container hierarchy
     */
    <T> Collection<T> getContainedBeans(Class<T> clazz);

    /**
     * Get the beans added to the connector that are EventListeners.
     * This is essentially equivalent to <code>getBeans(EventListener.class);</code>,
     * except that: <ul>
     *     <li>The result is precomputed, so it is more efficient</li>
     *     <li>The result is ordered by the order added.</li>
     *     <li>The result is immutable.</li>
     * </ul>
     * @see #getBeans(Class)
     * @return An unmodifiable list of EventListener beans
     */
    default List<EventListener> getEventListenerBeans()
    {
        return Collections.unmodifiableList(new ArrayList<>(getBeans(EventListener.class)));
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
