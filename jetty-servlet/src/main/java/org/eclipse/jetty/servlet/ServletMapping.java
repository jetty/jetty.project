//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
public class ServletMapping
{
    private String[] _pathSpecs;
    private String _servletName;
    private boolean _default;
    private Source _source;
    
    

    /* ------------------------------------------------------------ */
    public ServletMapping()
    {
        this (Source.EMBEDDED);
    }
    
    public ServletMapping(Source source)
    {
        _source = source;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return Returns the pathSpecs.
     */
    @ManagedAttribute(value="url patterns", readonly=true)
    public String[] getPathSpecs()
    {
        return _pathSpecs;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return Returns the servletName.
     */
    @ManagedAttribute(value="servlet name", readonly=true)
    public String getServletName()
    {
        return _servletName;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param pathSpecs The pathSpecs to set.
     */
    public void setPathSpecs(String[] pathSpecs)
    {
        _pathSpecs = pathSpecs;
    }
    
    
    /* ------------------------------------------------------------ */
    /** Test if the list of path specs contains a particular one.
     * @param pathSpec the path spec
     * @return true if path spec matches something in mappings
     */
    public boolean containsPathSpec (String pathSpec)
    {
        if (_pathSpecs == null || _pathSpecs.length == 0)
            return false;
        
        for (String p:_pathSpecs)
        {
            if (p.equals(pathSpec))
                return true;
        }
        return false;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param pathSpec The pathSpec to set.
     */
    public void setPathSpec(String pathSpec)
    {
        _pathSpecs = new String[]{pathSpec};
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param servletName The servletName to set.
     */
    public void setServletName(String servletName)
    {
        _servletName = servletName;
    }
    
    
    /* ------------------------------------------------------------ */
    @ManagedAttribute(value="default", readonly=true)
    public boolean isDefault()
    {
        return _default;
    }
    
    
    /* ------------------------------------------------------------ */
    public void setDefault(boolean fromDefault)
    {
        _default = fromDefault;
    }
    
    /* ------------------------------------------------------------ */
    public Source getSource ()
    {
        return _source;
    }

    /* ------------------------------------------------------------ */
    public String toString()
    {
        return (_pathSpecs==null?"[]":Arrays.asList(_pathSpecs).toString())+"=>"+_servletName; 
    }

    /* ------------------------------------------------------------ */
    public void dump(Appendable out, String indent) throws IOException
    {
        out.append(String.valueOf(this)).append("\n");
    }
}
