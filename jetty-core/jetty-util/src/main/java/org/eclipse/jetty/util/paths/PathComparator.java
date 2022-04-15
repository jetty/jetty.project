//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.paths;

import java.nio.file.Path;
import java.util.Comparator;

/**
 * Provider neutral {@link Path} comparator.
 * <p>
 * Sorting is done via the {@code Path.toUri()} objects.
 * This is useful when sorting Path objects across FileSystem types.
 * </p>
 */
public class PathComparator implements Comparator<Path>
{
    @Override
    public int compare(Path o1, Path o2)
    {
        return o1.toUri().compareTo(o2.toUri());
    }
}
