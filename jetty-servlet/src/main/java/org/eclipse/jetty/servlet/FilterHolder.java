// ========================================================================
// Copyright (c) 1996-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.servlet;

import javax.servlet.Filter;
import javax.servlet.FilterConfig;

import org.eclipse.jetty.util.log.Log;

/* --------------------------------------------------------------------- */
/** 
 * 
 */
public class FilterHolder extends Holder
{    
    /* ------------------------------------------------------------ */
    private transient Filter _filter;
    private transient Config _config;
        
    /* ---------------------------------------------------------------- */
    /** Constructor for Serialization.
     */
    public FilterHolder()
    {
    }   
    
    /* ---------------------------------------------------------------- */
    /** Constructor for Serialization.
     */
    public FilterHolder(Class filter)
    {
        super (filter);
    }

    /* ---------------------------------------------------------------- */
    /** Constructor for existing filter.
     */
    public FilterHolder(Filter filter)
    {
        setFilter(filter);
    }
    
    /* ------------------------------------------------------------ */
    public void doStart()
        throws Exception
    {
        super.doStart();
        
        if (!javax.servlet.Filter.class
            .isAssignableFrom(_class))
        {
            String msg = _class+" is not a javax.servlet.Filter";
            super.stop();
            throw new IllegalStateException(msg);
        }

        if (_filter==null)
            _filter=(Filter)newInstance();
        
        _filter = getServletHandler().customizeFilter(_filter);
        
        _config=new Config();
        _filter.init(_config);
    }

    /* ------------------------------------------------------------ */
    public void doStop()
        throws Exception
    {      
        if (_filter!=null)
        {
            try
            {
                destroyInstance(_filter);
            }
            catch (Exception e)
            {
                Log.warn(e);
            }
        }
        if (!_extInstance)
            _filter=null;
        
        _config=null;
        super.doStop();   
    }

    /* ------------------------------------------------------------ */
    public void destroyInstance (Object o)
    throws Exception
    {
        if (o==null)
            return;
        Filter f = (Filter)o;
        f.destroy();
        getServletHandler().customizeFilterDestroy(f);
    }

    /* ------------------------------------------------------------ */
    public synchronized void setFilter(Filter filter)
    {
        _filter=filter;
        _extInstance=true;
        setHeldClass(filter.getClass());
        if (getName()==null)
            setName(filter.getClass().getName());
    }
    
    /* ------------------------------------------------------------ */
    public Filter getFilter()
    {
        return _filter;
    }

    /* ------------------------------------------------------------ */
    public String toString()
    {
        return getName();
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class Config extends HolderConfig implements FilterConfig
    {
        /* ------------------------------------------------------------ */
        public String getFilterName()
        {
            return _name;
        }
    }
}





