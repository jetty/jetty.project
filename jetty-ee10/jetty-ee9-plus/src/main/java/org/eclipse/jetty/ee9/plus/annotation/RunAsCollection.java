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

package org.eclipse.jetty.ee9.plus.annotation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RunAsCollection
 * @deprecated class unused as of 9.4.28 due for removal in 10.0.0
 */
@Deprecated
public class RunAsCollection
{
    private static final Logger LOG = LoggerFactory.getLogger(RunAsCollection.class);

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

        RunAs runAs = (RunAs)_runAsMap.get(o.getClass().getName());
        if (runAs == null)
            return;

        runAs.setRunAs((ServletHolder)o);
    }
}
