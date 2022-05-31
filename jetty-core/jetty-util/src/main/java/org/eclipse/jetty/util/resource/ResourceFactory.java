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

/**
 * ResourceFactory.
 */
public interface ResourceFactory
{
    /**
     * Get a Resource from a provided String.
     * <p>
     * The behavior here is dependent on the
     * implementation of ResourceFactory.
     * The provided path can be resolved
     * against a known Resource, or can
     * be a from-scratch Resource.
     * </p>
     *
     * @param path The path to the resource
     * @return The resource, that might not actually exist (yet).
     * @throws IOException if unable to create Resource
     */
    // TODO this should move to Resource and be renamed resolve()
    Resource getResource(String path) throws IOException;
}
