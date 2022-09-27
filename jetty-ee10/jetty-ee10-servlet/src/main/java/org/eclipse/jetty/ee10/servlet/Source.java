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

package org.eclipse.jetty.ee10.servlet;

import org.eclipse.jetty.util.resource.Resource;

/**
 * Source
 *
 * The source of a web artifact: servlet, filter, mapping etc
 */
public class Source
{
    public static final Source EMBEDDED = new Source(Origin.EMBEDDED);
    public static final Source JAVAX_API = new Source(Origin.JAKARTA_API);

    public enum Origin
    {
        EMBEDDED,
        JAKARTA_API, DESCRIPTOR, ANNOTATION
    }

    public final Origin _origin;
    public final String _name;
    public Resource _resource;

    /**
     * A Source without a name/location.
     *
     * @param o the Origin of the artifact (servlet, filter, mapping etc)
     */
    public Source(Origin o)
    {
        this(o, (String)null);
    }

    /**
     * @param o the Origin of the artifact (servlet, filter, mapping etc)
     * @param clazz the class where the artifact was declared
     */
    public Source(Origin o, Class<?> clazz)
    {
        this(o, clazz.getName());
    }

    /**
     * @param o the Origin of the artifact (servlet, filter, mapping etc)
     * @param resource the location where the artifact was declared
     */
    public Source(Origin o, Resource resource)
    {
        this(o, resource.getURI().toASCIIString());
        _resource = resource;
    }

    /**
     * @param o the Origin of the artifact (servlet, filter, mapping etc)
     * @param name the name of the location where the artifact was declared (not a {@link Resource})
     */
    public Source(Origin o, String name)
    {
        if (o == null)
            throw new IllegalArgumentException("Origin is null");
        _origin = o;
        _name = name;
    }

    /**
     * @return the origin
     */
    public Origin getOrigin()
    {
        return _origin;
    }

    /**
     * @return the resource
     */
    public Resource getResource()
    {
        return _resource;
    }

    /**
     * @return the name
     */
    public String getName()
    {
        return _name;
    }

    @Override
    public String toString()
    {
        return _origin + ":" + (_name == null ? "<null>" : _name);
    }
}
