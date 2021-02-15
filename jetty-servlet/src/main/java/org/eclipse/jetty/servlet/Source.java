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

package org.eclipse.jetty.servlet;

/**
 * Source
 *
 * The source of a web artifact: servlet, filter, mapping etc
 */
public class Source
{
    public static final Source EMBEDDED = new Source(Origin.EMBEDDED, null);
    public static final Source JAVAX_API = new Source(Origin.JAVAX_API, null);

    public enum Origin
    {
        EMBEDDED, JAVAX_API, DESCRIPTOR, ANNOTATION
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

    /**
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString()
    {

        return _origin + ":" + _resource;
    }
}
