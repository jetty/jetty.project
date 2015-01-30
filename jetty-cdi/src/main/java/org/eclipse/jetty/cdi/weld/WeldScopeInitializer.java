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

package org.eclipse.jetty.cdi.weld;

import javax.inject.Inject;
import javax.servlet.ServletContext;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.jboss.weld.context.bound.BoundConversationContext;
import org.jboss.weld.context.bound.BoundRequestContext;
import org.jboss.weld.manager.BeanManagerImpl;

/**
 * CDI/Weld scopes initialization of WebSocket specific Weld Contexts.
 */
public class WeldScopeInitializer
{
    private static final Logger LOG = Log.getLogger(WeldScopeInitializer.class);
    
    @Inject
    private ServletContext servletContext;
    
    @Inject
    private BoundRequestContext boundRequestContext;
    
    @Inject
    private BoundConversationContext boundConversationContext;
    
    @Inject
    private BeanManagerImpl beanManager;
    
    private WeldConversationContext conversationContext;
    private WeldRequestContext requestContext;
    private WeldSessionContext sessionCOntext;

    public void activate(WebSocketContainerScope scope)
    {
        LOG.info("activate(WebSocketContainerScope:{})",scope);
        WeldConversationContext.activate(boundConversationContext);
        WeldSessionContext.activate(beanManager);
        WeldRequestContext.activate(boundRequestContext);
    }

    public void deactivate(WebSocketContainerScope scope)
    {
        LOG.info("deactivate(WebSocketContainerScope:{})",scope);
        WeldConversationContext.deactivate();
        WeldSessionContext.deactivate();
        WeldRequestContext.deactivate();
    }

    public void invalidate(WebSocketContainerScope scope)
    {
        LOG.info("invalidate(WebSocketContainerScope:{})",scope);
        WeldConversationContext.invalidate();
        WeldSessionContext.invalidate();
        WeldRequestContext.invalidate();
    }
}
