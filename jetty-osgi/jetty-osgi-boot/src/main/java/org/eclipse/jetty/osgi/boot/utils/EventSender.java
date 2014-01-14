//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.osgi.boot.utils;

import java.util.Dictionary;
import java.util.Hashtable;

import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;

/**
 * EventSender
 *
 * Utility class for emiting OSGi EventAdmin events
 * 
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
    private EventAdmin _eventAdmin;
    
    
    
    
    /* ------------------------------------------------------------ */
    /**
     * 
     */
    private EventSender ()
    {
        _myBundle = FrameworkUtil.getBundle(EventSender.class);
        ServiceReference ref = _myBundle.getBundleContext().getServiceReference(EventAdmin.class.getName());
        if (ref != null)
            _eventAdmin = (EventAdmin)_myBundle.getBundleContext().getService(ref);
    }
    
    
    

    /* ------------------------------------------------------------ */
    /**
     * @return
     */
    public static EventSender getInstance()
    {
        return __instance;
    }

    
    
    /* ------------------------------------------------------------ */
    /**
     * @param topic
     * @param wab
     * @param contextPath
     */
    public  void send (String topic, Bundle wab, String contextPath)
    {
        if (topic==null || wab==null || contextPath==null)
            return;
        
        send(topic, wab, contextPath, null);
    }
    
    
    
    /* ------------------------------------------------------------ */
    /**
     * @param topic
     * @param wab
     * @param contextPath
     * @param ex
     */
    public  void send (String topic, Bundle wab, String contextPath, Exception ex)
    {        
        if (_eventAdmin == null)
            return; 
        
        Dictionary<String,Object> props = new Hashtable<String,Object>();
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
        
        if (FAILED_EVENT.equalsIgnoreCase(topic)  && ex != null)
            props.put("exception", ex);

        _eventAdmin.sendEvent(new Event(topic, props));
    }
}
