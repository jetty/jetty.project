//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

    static Attributes unwrap(Attributes attributes)
    {
        while (attributes instanceof Wrapper)
        {
            attributes = ((Wrapper)attributes).getAttributes();
        }
        return attributes;
    }

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
