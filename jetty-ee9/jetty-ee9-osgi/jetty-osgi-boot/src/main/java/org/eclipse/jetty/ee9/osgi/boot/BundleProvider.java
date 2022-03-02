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

package org.eclipse.jetty.ee9.osgi.boot;

import org.osgi.framework.Bundle;

/**
 * BundleProvider
 *
 * Jetty DeploymentManager Provider api for webapps or ContextHandlers that are discovered as osgi bundles.
 */
public interface BundleProvider
{
    public boolean bundleAdded(Bundle bundle) throws Exception;

    public boolean bundleRemoved(Bundle bundle) throws Exception;
}
