//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.cdi;

import java.util.logging.Logger;

import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.BeforeBeanDiscovery;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import javax.enterprise.inject.spi.ProcessBean;
import javax.enterprise.inject.spi.ProcessInjectionPoint;
import javax.enterprise.inject.spi.ProcessInjectionTarget;

import org.jboss.weld.bootstrap.events.ContainerEvent;

public class EventDebugExtension implements Extension
{
    private static final Logger LOG = Logger.getLogger(EventDebugExtension.class.getName());

    void afterBeanDiscovery(@Observes AfterBeanDiscovery abd)
    {
        LOG.info("finished the scanning process");
    }

    void beforeBeanDiscovery(@Observes BeforeBeanDiscovery bbd)
    {
        LOG.info("beginning the scanning process");
    }

    void containerEvent(@Observes ContainerEvent evt)
    {
        LOG.info("container event: " + evt);
    }

    <T> void processAnnotatedType(@Observes ProcessAnnotatedType<T> pat)
    {
        LOG.info("scanning type: " + pat.getAnnotatedType().getJavaClass().getName());
    }

    <T> void processBean(@Observes ProcessBean<T> bean)
    {
        LOG.info("process bean: " + bean.getBean());
    }

    <T, X> void processInjectionPoint(@Observes ProcessInjectionPoint<T, X> inj)
    {
        LOG.info("process injection point: " + inj.getInjectionPoint());
    }

    <T> void processInjectionPoint(@Observes ProcessInjectionTarget<T> inj)
    {
        LOG.info("process injection target: " + inj.getInjectionTarget());
    }
}
