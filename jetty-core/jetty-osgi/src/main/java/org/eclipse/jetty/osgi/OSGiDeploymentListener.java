//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.osgi;

import org.eclipse.jetty.deploy.Deployer;
import org.eclipse.jetty.osgi.util.EventSender;
import org.eclipse.jetty.osgi.util.Util;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.osgi.framework.Bundle;

public class OSGiDeploymentListener implements Deployer.Listener
{
    @Override
    public void onDeploying(ContextHandler contextHandler)
    {
        Bundle bundle = (Bundle)contextHandler.getAttribute(BundleMetadata.BUNDLE);
        if (bundle == null)
        {
            EventSender.getInstance().send(EventSender.DEPLOYING_EVENT, bundle, contextHandler.getContextPath());
        }
    }

    @Override
    public void onDeployed(ContextHandler contextHandler)
    {
        Bundle bundle = (Bundle)contextHandler.getAttribute(BundleMetadata.BUNDLE);
        if (bundle == null)
        {
            Util.registerAsOSGiService(contextHandler);
            EventSender.getInstance().send(EventSender.DEPLOYED_EVENT, bundle, contextHandler.getContextPath());
        }
    }

    @Override
    public void onUndeploying(ContextHandler contextHandler)
    {
        Bundle bundle = (Bundle)contextHandler.getAttribute(BundleMetadata.BUNDLE);
        if (bundle == null)
        {
            EventSender.getInstance().send(EventSender.UNDEPLOYING_EVENT, bundle, contextHandler.getContextPath());
        }
    }

    @Override
    public void onUndeployed(ContextHandler contextHandler)
    {
        Bundle bundle = (Bundle)contextHandler.getAttribute(BundleMetadata.BUNDLE);
        if (bundle == null)
        {
            EventSender.getInstance().send(EventSender.UNDEPLOYED_EVENT, bundle, contextHandler.getContextPath());
            Util.deregisterAsOSGiService(contextHandler);
        }
    }

    @Override
    public void onFailure(ContextHandler contextHandler, Throwable cause)
    {
        Bundle bundle = (Bundle)contextHandler.getAttribute(BundleMetadata.BUNDLE);
        if (bundle == null)
        {
            EventSender.getInstance().send(EventSender.FAILED_EVENT, bundle, contextHandler.getContextPath());
        }
    }
}
