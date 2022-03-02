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

package org.eclipse.jetty.ee9.servlet;

/**
 * Source
 *
 * The source of a web artifact: servlet, filter, mapping etc
 */
public class Source
{
    public static final Source EMBEDDED = new Source(Origin.EMBEDDED, null);
    public static final Source JAVAX_API = new Source(Origin.JAKARTA_API, null);

    public enum Origin
    {
        EMBEDDED,
        JAKARTA_API, DESCRIPTOR, ANNOTATION
    }

    public Origin _origin;
    public String _resource;

    /**
     * @param o the Origin of the artifact (servlet, filter, mapping etc)
     * @param resource the location where the artifact was declared
     */
    public Source(Origin o, String resource)
    {
        if (o == null)
            throw new IllegalArgumentException("Origin is null");
        _origin = o;
        _resource = resource;
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
    public String getResource()
    {
        return _resource;
    }

    @Override
    public String toString()
    {

        return _origin + ":" + _resource;
    }
}
