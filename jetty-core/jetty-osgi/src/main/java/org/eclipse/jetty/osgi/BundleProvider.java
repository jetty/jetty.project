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

import org.osgi.framework.Bundle;

/**
 * Interfaces the deployment of a bundle in osgi to the deployment of a ContextHandler for it
 */
public interface BundleProvider
{
    boolean bundleAdded(Bundle bundle) throws Exception;

    boolean bundleRemoved(Bundle bundle) throws Exception;
}
