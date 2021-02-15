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

package org.eclipse.jetty.plus.annotation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * RunAsCollection
 * @deprecated class unused as of 9.4.28 due for removal in 10.0.0
 */
@Deprecated
public class RunAsCollection
{
    private static final Logger LOG = Log.getLogger(RunAsCollection.class);

    public static final String RUNAS_COLLECTION = "org.eclipse.jetty.runAsCollection";
    private ConcurrentMap<String, RunAs> _runAsMap = new ConcurrentHashMap<String, RunAs>(); //map of classname to run-as

    public void add(RunAs runAs)
    {
        if ((runAs == null) || (runAs.getTargetClassName() == null))
            return;

        if (LOG.isDebugEnabled())
            LOG.debug("Adding run-as for class=" + runAs.getTargetClassName());
        RunAs prev = _runAsMap.putIfAbsent(runAs.getTargetClassName(), runAs);
        if (prev != null)
            LOG.warn("Run-As {} on class {} ignored, already run-as {}", runAs.getRoleName(), runAs.getTargetClassName(), prev.getRoleName());
    }

    public RunAs getRunAs(Object o)
    {
        if (o == null)
            return null;

        return (RunAs)_runAsMap.get(o.getClass().getName());
    }

    public void setRunAs(Object o)
    {
        if (o == null)
            return;

        if (!ServletHolder.class.isAssignableFrom(o.getClass()))
            return;

        RunAs runAs = _runAsMap.get(o.getClass().getName());
        if (runAs == null)
            return;

        runAs.setRunAs((ServletHolder)o);
    }
}
