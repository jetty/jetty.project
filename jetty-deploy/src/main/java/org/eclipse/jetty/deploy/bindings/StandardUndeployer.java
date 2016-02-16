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

package org.eclipse.jetty.deploy.bindings;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppLifeCycle;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class StandardUndeployer implements AppLifeCycle.Binding
{
    private static final Logger LOG = Log.getLogger(StandardUndeployer.class);

    @Override
    public String[] getBindingTargets()
    {
        return new String[]
        { "undeploying" };
    }

    @Override
    public void processBinding(Node node, App app) throws Exception
    {
        ContextHandler handler = app.getContextHandler();
        ContextHandlerCollection chcoll = app.getDeploymentManager().getContexts();

        recursiveRemoveContext(chcoll,handler);
    }

    private void recursiveRemoveContext(HandlerCollection coll, ContextHandler context)
    {
        Handler children[] = coll.getHandlers();
        int originalCount = children.length;

        for (int i = 0, n = children.length; i < n; i++)
        {
            Handler child = children[i];
            LOG.debug("Child handler {}",child);
            if (child.equals(context))
            {
                LOG.debug("Removing handler {}",child);
                coll.removeHandler(child);
                child.destroy();
                if (LOG.isDebugEnabled())
                    LOG.debug("After removal: {} (originally {})",coll.getHandlers().length,originalCount);
            }
            else if (child instanceof HandlerCollection)
            {
                recursiveRemoveContext((HandlerCollection)child,context);
            }
        }
    }
}
