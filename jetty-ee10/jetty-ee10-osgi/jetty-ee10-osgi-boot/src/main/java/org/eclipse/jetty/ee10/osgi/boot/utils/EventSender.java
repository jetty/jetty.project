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

package org.eclipse.jetty.ee10.osgi.boot.utils;

import java.util.Dictionary;
import java.util.Hashtable;

import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Utility class for emitting OSGi EventAdmin events
 */
public class EventSender
{
    //OSGi Event Admin events for webapps
    public static final String DEPLOYING_EVENT = "org/osgi/service/web/DEPLOYING";
    public static final String DEPLOYED_EVENT = "org/osgi/service/web/DEPLOYED";
    public static final String UNDEPLOYING_EVENT = "org/osgi/service/web/UNDEPLOYING";
    public static final String UNDEPLOYED_EVENT = "org/osgi/service/web/UNDEPLOYED";
    public static final String FAILED_EVENT = "org/osgi/service/web/FAILED";

    private static final EventSender __instance = new EventSender();
    private Bundle _myBundle;
    private ServiceTracker _serviceTracker;

    private EventSender()
    {
        _myBundle = FrameworkUtil.getBundle(EventSender.class);
        _serviceTracker = new ServiceTracker(_myBundle.getBundleContext(), EventAdmin.class.getName(), null);
        _serviceTracker.open();
    }

    public static EventSender getInstance()
    {
        return __instance;
    }

    public void send(String topic, Bundle wab, String contextPath)
    {
        if (topic == null || wab == null || contextPath == null)
            return;

        send(topic, wab, contextPath, null);
    }

    public void send(String topic, Bundle wab, String contextPath, Exception ex)
    {
        EventAdmin service = (EventAdmin)_serviceTracker.getService();
        if (service != null)
        {
            Dictionary<String, Object> props = new Hashtable<>();
            props.put("bundle.symbolicName", wab.getSymbolicName());
            props.put("bundle.id", wab.getBundleId());
            props.put("bundle", wab);
            props.put("bundle.version", wab.getVersion());
            props.put("context.path", contextPath);
            props.put("timestamp", System.currentTimeMillis());
            props.put("extender.bundle", _myBundle);
            props.put("extender.bundle.symbolicName", _myBundle.getSymbolicName());
            props.put("extender.bundle.id", _myBundle.getBundleId());
            props.put("extender.bundle.version", _myBundle.getVersion());

            if (FAILED_EVENT.equalsIgnoreCase(topic) && ex != null)
                props.put("exception", ex);

            service.sendEvent(new Event(topic, props));
        }
    }
}
