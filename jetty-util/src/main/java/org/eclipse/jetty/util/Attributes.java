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

package org.eclipse.jetty.util;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;

/**
 * Attributes.
 * Interface commonly used for storing attributes.
 */
public interface Attributes
{
    void removeAttribute(String name);

    void setAttribute(String name, Object attribute);

    Object getAttribute(String name);

    Set<String> getAttributeNameSet();

    default Enumeration<String> getAttributeNames()
    {
        return Collections.enumeration(getAttributeNameSet());
    }

    void clearAttributes();

    /** Unwrap all  {@link Wrapper}s of the attributes
     * @param attributes The attributes to unwrap, which may be a  {@link Wrapper}.
     * @return The core attributes
     */
    static Attributes unwrap(Attributes attributes)
    {
        while (attributes instanceof Wrapper)
        {
            attributes = ((Wrapper)attributes).getAttributes();
        }
        return attributes;
    }

    /** Unwrap attributes to a specific attribute  {@link Wrapper}.
     * @param attributes The attributes to unwrap, which may be a {@link Wrapper}
     * @param target The target  {@link Wrapper} class.
     * @param <T> The type of the target  {@link Wrapper}.
     * @return The outermost {@link Wrapper} of the matching type of null if not found.
     */
    static <T extends Attributes.Wrapper> T unwrap(Attributes attributes, Class<T> target)
    {
        while (attributes instanceof Wrapper)
        {
            if (target.isAssignableFrom(attributes.getClass()))
                return (T)attributes;
            attributes = ((Wrapper)attributes).getAttributes();
        }
        return null;
    }

    /**
     * A Wrapper of attributes
     */
    abstract class Wrapper implements Attributes
    {
        protected final Attributes _attributes;

        public Wrapper(Attributes attributes)
        {
            _attributes = attributes;
        }

        public Attributes getAttributes()
        {
            return _attributes;
        }

        @Override
        public void removeAttribute(String name)
        {
            _attributes.removeAttribute(name);
        }

        @Override
        public void setAttribute(String name, Object attribute)
        {
            _attributes.setAttribute(name, attribute);
        }

        @Override
        public Object getAttribute(String name)
        {
            return _attributes.getAttribute(name);
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            return _attributes.getAttributeNameSet();
        }

        @Override
        public void clearAttributes()
        {
            _attributes.clearAttributes();
        }
    }
}
