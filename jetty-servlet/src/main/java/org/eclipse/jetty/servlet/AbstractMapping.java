//========================================================================
//Copyright (c) Webtide LLC
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//
//The Apache License v2.0 is available at
//http://www.apache.org/licenses/LICENSE-2.0.txt
//
//You may elect to redistribute this code under either of these licenses.
//========================================================================

package org.eclipse.jetty.servlet;

import java.io.IOException;

import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;


/* ------------------------------------------------------------ */
/**
 */
public abstract class AbstractMapping implements Mapping, Dumpable
{
    private String _entityName;
    private String[] _pathSpecs;
    private String _contextBasis;
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.servlet.Mapping#setEntityName(java.lang.String)
     */
    public void setEntityName(String name)
    {
        _entityName = name;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.servlet.Mapping#getEntityName()
     */
    public String getEntityName()
    {
        return _entityName;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.servlet.Mapping#setPathSpecs(java.lang.String[])
     */
    public void setPathSpecs(String[] pathSpecs)
    {
        _pathSpecs = pathSpecs;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.servlet.Mapping#setPathSpec(java.lang.String)
     */
    public void setPathSpec(String pathSpec)
    {
        _pathSpecs = new String[]{pathSpec};
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.servlet.Mapping#getPathSpecs()
     */
    public String[] getPathSpecs()
    {
        return _pathSpecs;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.servlet.Mapping#getContextBasis()
     */
    public String getContextBasis()
    {
        return _contextBasis;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.servlet.Mapping#setContextBasis(java.lang.String)
     */
    public void setContextBasis(String basis)
    {
        _contextBasis = basis;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return TypeUtil.asList(getPathSpecs())+"=>"+getEntityName(); 
    }
    
    /* ------------------------------------------------------------ */
    public void dump(Appendable out, String indent) throws IOException
    {
        out.append(String.valueOf(this)).append("\n");
    }

    /* ------------------------------------------------------------ */
    public String dump()
    {
        return AggregateLifeCycle.dump(this);
    }    
}
