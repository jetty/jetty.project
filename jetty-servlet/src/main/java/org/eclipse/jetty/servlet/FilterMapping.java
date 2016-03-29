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
import java.util.EnumSet;

import javax.servlet.DispatcherType;

import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;

@ManagedObject("Filter Mappings")
public class FilterMapping implements Dumpable
{
    /** Dispatch types */
    public static final int DEFAULT=0;
    public static final int REQUEST=1;
    public static final int FORWARD=2;
    public static final int INCLUDE=4;
    public static final int ERROR=8;
    public static final int ASYNC=16;
    public static final int ALL=31;


    /* ------------------------------------------------------------ */
    /** Dispatch type from name
     * @param type the type name
     * @return the dispatcher type
     */
    public static DispatcherType dispatch(String type)
    {
        if ("request".equalsIgnoreCase(type))
            return DispatcherType.REQUEST;
        if ("forward".equalsIgnoreCase(type))
            return DispatcherType.FORWARD;
        if ("include".equalsIgnoreCase(type))
            return DispatcherType.INCLUDE;
        if ("error".equalsIgnoreCase(type))
            return DispatcherType.ERROR;
        if ("async".equalsIgnoreCase(type))
            return DispatcherType.ASYNC;
        throw new IllegalArgumentException(type);
    }

    /* ------------------------------------------------------------ */
    /** Dispatch type from name
     * @param type the dispatcher type
     * @return the type constant ({@link #REQUEST}, {@link #ASYNC}, {@link #FORWARD}, {@link #INCLUDE}, or {@link #ERROR})
     */
    public static int dispatch(DispatcherType type)
    {
        switch(type)
        {
          case REQUEST:
                  return REQUEST;
          case ASYNC:
                  return ASYNC;
          case FORWARD:
                  return FORWARD;
          case INCLUDE:
                  return INCLUDE;
          case ERROR:
                  return ERROR;
        }
        throw new IllegalArgumentException(type.toString());
    }


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */


    private int _dispatches=DEFAULT;
    private String _filterName;
    private transient FilterHolder _holder;
    private String[] _pathSpecs;
    private String[] _servletNames;

    /* ------------------------------------------------------------ */
    public FilterMapping()
    {}

    /* ------------------------------------------------------------ */
    /** Check if this filter applies to a path.
     * @param path The path to check or null to just check type
     * @param type The type of request: __REQUEST,__FORWARD,__INCLUDE, __ASYNC or __ERROR.
     * @return True if this filter applies
     */
    boolean appliesTo(String path, int type)
    {
        if (appliesTo(type))
        {
            for (int i=0;i<_pathSpecs.length;i++)
                if (_pathSpecs[i]!=null &&  PathMap.match(_pathSpecs[i], path,true))
                    return true;
        }

        return false;
    }

    /* ------------------------------------------------------------ */
    /** Check if this filter applies to a particular dispatch type.
     * @param type The type of request:
     *      {@link Handler#REQUEST}, {@link Handler#FORWARD}, {@link Handler#INCLUDE} or {@link Handler#ERROR}.
     * @return <code>true</code> if this filter applies
     */
    boolean appliesTo(int type)
    {
        if (_dispatches==0)
                return type==REQUEST || type==ASYNC && _holder.isAsyncSupported();
        return (_dispatches&type)!=0;
    }

    /* ------------------------------------------------------------ */
    public boolean appliesTo(DispatcherType t)
    {
        return appliesTo(dispatch(t));
    }
    
    /* ------------------------------------------------------------ */
    public boolean isDefaultDispatches()
    {
        return _dispatches==0;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return Returns the filterName.
     */
    @ManagedAttribute(value="filter name", readonly=true)
    public String getFilterName()
    {
        return _filterName;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the holder.
     */
    FilterHolder getFilterHolder()
    {
        return _holder;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the pathSpec.
     */
    @ManagedAttribute(value="url patterns", readonly=true)
    public String[] getPathSpecs()
    {
        return _pathSpecs;
    }

    /* ------------------------------------------------------------ */
    public void setDispatcherTypes(EnumSet<DispatcherType> dispatcherTypes)
    {
        _dispatches=DEFAULT;
        if (dispatcherTypes!=null)
        {
            if (dispatcherTypes.contains(DispatcherType.ERROR))
                _dispatches|=ERROR;
            if (dispatcherTypes.contains(DispatcherType.FORWARD))
                _dispatches|=FORWARD;
            if (dispatcherTypes.contains(DispatcherType.INCLUDE))
                _dispatches|=INCLUDE;
            if (dispatcherTypes.contains(DispatcherType.REQUEST))
                _dispatches|=REQUEST;
            if (dispatcherTypes.contains(DispatcherType.ASYNC))
                _dispatches|=ASYNC;
        }
    }

    /* ------------------------------------------------------------ */
    public EnumSet<DispatcherType> getDispatcherTypes()
    {
        EnumSet<DispatcherType> dispatcherTypes = EnumSet.noneOf(DispatcherType.class);
        if ((_dispatches & ERROR) == ERROR)
            dispatcherTypes.add(DispatcherType.ERROR);
        if ((_dispatches & FORWARD) == FORWARD)
            dispatcherTypes.add(DispatcherType.FORWARD);
        if ((_dispatches & INCLUDE) == INCLUDE)
            dispatcherTypes.add(DispatcherType.INCLUDE);
        if ((_dispatches & REQUEST) == REQUEST)
            dispatcherTypes.add(DispatcherType.REQUEST);
        if ((_dispatches & ASYNC) == ASYNC)
            dispatcherTypes.add(DispatcherType.ASYNC);
        return dispatcherTypes;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param dispatches The dispatches to set.
     * @see #DEFAULT
     * @see #REQUEST
     * @see #ERROR
     * @see #FORWARD
     * @see #INCLUDE
     */
    public void setDispatches(int dispatches)
    {
        _dispatches = dispatches;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param filterName The filterName to set.
     */
    public void setFilterName(String filterName)
    {
        _filterName = filterName;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param holder The holder to set.
     */
    void setFilterHolder(FilterHolder holder)
    {
        _holder = holder;
        setFilterName(holder.getName());
    }

    /* ------------------------------------------------------------ */
    /**
     * @param pathSpecs The Path specifications to which this filter should be mapped.
     */
    public void setPathSpecs(String[] pathSpecs)
    {
        _pathSpecs = pathSpecs;
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
     * @return Returns the servletName.
     */
    @ManagedAttribute(value="servlet names", readonly=true)
    public String[] getServletNames()
    {
        return _servletNames;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param servletNames Maps the {@link #setFilterName(String) named filter} to multiple servlets
     * @see #setServletName
     */
    public void setServletNames(String[] servletNames)
    {
        _servletNames = servletNames;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param servletName Maps the {@link #setFilterName(String) named filter} to a single servlet
     * @see #setServletNames
     */
    public void setServletName(String servletName)
    {
        _servletNames = new String[]{servletName};
    }

    /* ------------------------------------------------------------ */
    public String toString()
    {
        return
        TypeUtil.asList(_pathSpecs)+"/"+
        TypeUtil.asList(_servletNames)+"=="+
        _dispatches+"=>"+
        _filterName;
    }

    /* ------------------------------------------------------------ */
    public void dump(Appendable out, String indent) throws IOException
    {
        out.append(String.valueOf(this)).append("\n");
    }

    /* ------------------------------------------------------------ */
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }
}
