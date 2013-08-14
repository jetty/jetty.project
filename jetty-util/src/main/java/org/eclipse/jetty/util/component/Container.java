//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.util.Collection;

public interface Container
{
    public boolean addBean(Object o);

    /**
     * @return the list of beans known to this aggregate
     * @see #getBean(Class)
     */
    public Collection<Object> getBeans();

    /**
     * @param clazz the class of the beans
     * @return the list of beans of the given class (or subclass)
     * @see #getBeans()
     */
    public <T> Collection<T> getBeans(Class<T> clazz);

    /**
     * @param clazz the class of the bean
     * @return the first bean of a specific class (or subclass), or null if no such bean exist
     */
    public <T> T getBean(Class<T> clazz);

    /**
     * Removes the given bean.
     * @return whether the bean was removed
     */
    public boolean removeBean(Object o);
    
    /**
     * A listener for Container events.
     * If an added bean implements this interface it will receive the events
     * for this container.
     */
    public interface Listener
    {
        void beanAdded(Container parent,Object child);
        void beanRemoved(Container parent,Object child);
    }
    
    /**
     * Inherited Listener.
     * If an added bean implements this interface, then it will 
     * be added to all contained beans that are themselves Containers
     */
    public interface InheritedListener extends Listener
    {
    }
}
