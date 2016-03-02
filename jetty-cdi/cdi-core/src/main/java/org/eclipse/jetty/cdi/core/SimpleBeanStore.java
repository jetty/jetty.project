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

package org.eclipse.jetty.cdi.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.enterprise.context.spi.Contextual;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class SimpleBeanStore
{
    private static final Logger LOG = Log.getLogger(SimpleBeanStore.class);

    public Map<Contextual<?>, List<ScopedInstance<?>>> beans = new HashMap<>();

    public void addBean(ScopedInstance<?> instance)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("addBean({})",instance);
        }
        List<ScopedInstance<?>> instances = getBeans(instance.bean);
        if (instances == null)
        {
            instances = new ArrayList<>();
            beans.put(instance.bean,instances);
        }
        instances.add(instance);
    }

    public void clear()
    {
        beans.clear();
    }

    public void destroy()
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("destroy() - {} beans",beans.size());
        }
        for (List<ScopedInstance<?>> instances : beans.values())
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("destroying - {} instance(s)",instances.size());
            }
            for (ScopedInstance<?> instance : instances)
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("destroying instance {}",instance);
                }
                instance.destroy();
            }
        }
    }

    public List<ScopedInstance<?>> getBeans(Contextual<?> contextual)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("getBeans({})",contextual);
        }
        return beans.get(contextual);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%X[size=%d]",this.getClass().getSimpleName(),hashCode(),beans.size());
    }
}