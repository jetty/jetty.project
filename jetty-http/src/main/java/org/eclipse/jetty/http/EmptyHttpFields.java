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

package org.eclipse.jetty.http;

import java.util.Collections;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.stream.Stream;

class EmptyHttpFields extends HttpFields.Immutable
{
    public EmptyHttpFields()
    {
        super(new HttpField[0]);
    }

    @Override
    public Iterator<HttpField> iterator()
    {
        return Collections.emptyIterator();
    }

    @Override
    public void forEach(Consumer<? super HttpField> action)
    {
        // no-op
    }

    @Override
    public Stream<HttpField> stream()
    {
        return Stream.empty();
    }
}
