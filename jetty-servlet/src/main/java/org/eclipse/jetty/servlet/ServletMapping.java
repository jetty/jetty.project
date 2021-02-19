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

import java.io.IOException;
import java.util.Arrays;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject("Servlet Mapping")
public class ServletMapping extends Mapping
{
    private String _servletName;
    private boolean _default;

    public ServletMapping()
    {
        this(Source.EMBEDDED);
    }

    public ServletMapping(Source source)
    {
        super(source);
    }

    /**
     * @return Returns the servletName.
     */
    @ManagedAttribute(value = "servlet name", readonly = true)
    public String getServletName()
    {
        return _servletName;
    }

    /**
     * @param servletName The servletName to set.
     */
    public void setServletName(String servletName)
    {
        _servletName = servletName;
    }

    @ManagedAttribute(value = "default", readonly = true)
    public boolean isDefault()
    {
        return _default;
    }

    public void setDefault(boolean fromDefault)
    {
        _default = fromDefault;
    }

    @Override
    public String toString()
    {
        return Arrays.asList(toPathSpecs()) +
            "/" + getSource() +
            "=>" + _servletName;
    }

    public void dump(Appendable out, String indent) throws IOException
    {
        out.append(String.valueOf(this)).append("\n");
    }
}
