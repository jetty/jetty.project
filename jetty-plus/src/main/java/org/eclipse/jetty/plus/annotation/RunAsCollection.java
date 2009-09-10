// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.plus.annotation;

import java.util.HashMap;

import javax.servlet.ServletException;

import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;


/**
 * RunAsCollection
 *
 *
 */
public class RunAsCollection
{
    public static final String RUNAS_COLLECTION = "org.eclipse.jetty.runAsCollection";
    private HashMap _runAsMap = new HashMap();//map of classname to run-as
    
    
    public void add (RunAs runAs)
    {
        if ((runAs==null) || (runAs.getTargetClassName()==null)) 
            return;
        
        if (Log.isDebugEnabled())
            Log.debug("Adding run-as for class="+runAs.getTargetClassName());
        _runAsMap.put(runAs.getTargetClassName(), runAs);
    }

    public RunAs getRunAs (Object o)
    throws ServletException
    {
        if (o==null)
            return null;
        
        if (!(o instanceof ServletHolder))
            return null;

        ServletHolder holder = (ServletHolder)o;

        String className = holder.getClassName();
        return (RunAs)_runAsMap.get(className);
    }
    
    public void setRunAs(Object o, SecurityHandler securityHandler)
    throws ServletException
    {
        if (o==null)
            return;
        
        if (!(o instanceof ServletHolder))
            return;

        ServletHolder holder = (ServletHolder)o;

        String className = holder.getClassName();
        RunAs runAs = (RunAs)_runAsMap.get(className);
        if (runAs == null)
            return;

        runAs.setRunAs(holder, securityHandler); 
    }

}
