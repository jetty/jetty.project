// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.deploy.bindings;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppLifeCycle;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.log.Log;

public class StandardUndeployer implements AppLifeCycle.Binding
{
    public String[] getBindingTargets()
    {
        return new String[]
        { "undeploying" };
    }

    public void processBinding(Node node, App app) throws Exception
    {
        ContextHandler handler = app.getContextHandler();
        ContextHandlerCollection chcoll = app.getDeploymentManager().getContexts();

        recursiveRemoveContext(chcoll,handler);

        app.getDeploymentManager().removeApp(app);
    }

    private void recursiveRemoveContext(HandlerCollection coll, ContextHandler context)
    {
        Handler children[] = coll.getHandlers();
        int originalCount = children.length;

        for (int i = 0, n = children.length; i < n; i++)
        {
            Handler child = children[i];
            Log.info("Child handler: " + child);
            if (child.equals(context))
            {
                Log.info("Removing handler: " + child);
                coll.removeHandler(child);
                Log.info(String.format("After removal: %d (originally %d)",coll.getHandlers().length,originalCount));
            }
            else if (child instanceof HandlerCollection)
            {
                recursiveRemoveContext((HandlerCollection)child,context);
            }
        }
    }
}
