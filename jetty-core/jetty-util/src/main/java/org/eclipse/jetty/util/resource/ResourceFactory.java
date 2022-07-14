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
public interface ResourceFactory
{
    /**
     * <p>
     * Returns the resource contained inside the current resource with the
     * given name, which may or may not exist.
     * </p>
     * <p>
     * The {@code subUriPath} parameter is interpreted like {@link URI#resolve(String)} would, except for a few convenient differences:
     * <ul>
     *     <li>{@code subUriPath} must not contain URI-invalid characters (<code>', [, %, ?</code> ...)</li>
     *     <li>{@code subUriPath} can contain escaped characters that are going to be correctly interpreted</li>
     *     <li>All prepended slashes are ignored</li>
     *     <li>If the resulting resource provably points to an existing directory, a / is automatically appended</li>
     * </ul>
     *</p>
     * @param subUriPath The path segment to add.
     * @return the Resource for the resolved path within this Resource, never null
     * @throws IOException if unable to resolve the path
     */
    Resource resolve(String subUriPath) throws IOException;
}
