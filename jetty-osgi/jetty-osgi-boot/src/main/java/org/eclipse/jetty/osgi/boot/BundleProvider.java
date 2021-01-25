//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.osgi.boot;

import org.osgi.framework.Bundle;

/**
 * BundleProvider
 *
 * Jetty DeploymentManager Provider api for webapps or ContextHandlers that are discovered as osgi bundles.
 */
public interface BundleProvider
{
    boolean bundleAdded(Bundle bundle) throws Exception;

    boolean bundleRemoved(Bundle bundle) throws Exception;
}
