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

package org.eclipse.jetty.util.resource;

import java.io.IOException;
import java.net.URI;

/**
 * ResourceFactory.
 */
// TODO remove
public interface ResourceFactory
{
    /**
     * Returns the resource contained inside the current resource with the
     * given name, which may or may not exist.
     *
     * @param subPath The path segment to add. It is going to be interpreted as a URI sub-path, see {@link URI#resolve(String)}.
     * @return the Resource for the resolved path within this Resource, never null
     * @throws IOException if unable to resolve the path
     */
    Resource resolve(String subPath) throws IOException;
}
