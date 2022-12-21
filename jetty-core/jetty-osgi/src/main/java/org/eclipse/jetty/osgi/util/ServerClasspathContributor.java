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

package org.eclipse.jetty.osgi.util;

import java.util.List;

import org.osgi.framework.Bundle;

public interface ServerClasspathContributor
{
    /**
     * Get bundles that should be on the Server classpath,
     * and should be scanned for annotations/tlds/resources etc
     * 
     * @return list of Bundles to be scanned and put on server classpath
     */
    List<Bundle> getScannableBundles();
}
