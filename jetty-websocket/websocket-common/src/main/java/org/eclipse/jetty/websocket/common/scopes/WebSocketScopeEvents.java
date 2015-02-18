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

package org.eclipse.jetty.websocket.common.scopes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WebSocketScopeEvents
{
    private List<WebSocketScopeListener> scopeListeners;

    public void addScopeListener(WebSocketScopeListener listener)
    {
        if (scopeListeners == null)
        {
            scopeListeners = new ArrayList<>();
        }
        scopeListeners.add(listener);
    }

    public void fireContainerActivated(WebSocketContainerScope container)
    {
        if (scopeListeners != null)
        {
            for (WebSocketScopeListener listener : scopeListeners)
            {
                listener.onWebSocketContainerActivated(container);
            }
        }
    }

    public void fireContainerDeactivated(WebSocketContainerScope container)
    {
        if (scopeListeners != null)
        {
            for (WebSocketScopeListener listener : scopeListeners)
            {
                listener.onWebSocketContainerDeactivated(container);
            }
        }
    }

    public void fireSessionActivated(WebSocketSessionScope session)
    {
        if (scopeListeners != null)
        {
            for (WebSocketScopeListener listener : scopeListeners)
            {
                listener.onWebSocketSessionActivated(session);
            }
        }
    }

    public void fireSessionDeactivated(WebSocketSessionScope session)
    {
        if (scopeListeners != null)
        {
            for (WebSocketScopeListener listener : scopeListeners)
            {
                listener.onWebSocketSessionDeactivated(session);
            }
        }
    }

    public List<WebSocketScopeListener> getScopeListeners()
    {
        if (scopeListeners == null)
        {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(scopeListeners);
    }

    public void removeScopeListener(WebSocketScopeListener listener)
    {
        if (scopeListeners == null)
        {
            return;
        }
        scopeListeners.remove(listener);
    }
}
