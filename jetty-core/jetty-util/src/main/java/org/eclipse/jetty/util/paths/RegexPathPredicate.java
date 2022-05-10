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

package org.eclipse.jetty.util.paths;

import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Simple Regex against {@code Path.toString()}
 */
public class RegexPathPredicate implements Predicate<Path>
{
    private final Predicate<String> fullPathPredicate;

    public RegexPathPredicate(String regex)
    {
        this.fullPathPredicate = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).asMatchPredicate();
    }

    @Override
    public boolean test(Path path)
    {
        return fullPathPredicate.test(path.toString());
    }
}
