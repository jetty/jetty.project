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

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;

import org.eclipse.jetty.server.Request;

/**
 * <p>An abstract rule that, upon matching a certain condition, may wrap
 * the {@code Request} or the {@code Processor} to execute custom logic.</p>
 */
public abstract class Rule
{
    private boolean _terminating;

    /**
     * <p>Tests whether the given {@code Request} should apply, and if so the rule logic is triggered.</p>
     *
     * @param input the input {@code Request} and {@code Processor}
     * @return the possibly wrapped {@code Request} and {@code Processor}, or {@code null} if the rule did not match
     * @throws IOException if applying the rule failed
     */
    public abstract Request.WrapperProcessor matchAndApply(Request.WrapperProcessor input) throws IOException;

    /**
     * @return when {@code true}, rules after this one are not processed
     */
    public boolean isTerminating()
    {
        return _terminating;
    }

    public void setTerminating(boolean value)
    {
        _terminating = value;
    }

    /**
     * Returns the handling and terminating flag values.
     */
    @Override
    public String toString()
    {
        return "%s@%x[terminating=%b]".formatted(getClass().getSimpleName(), hashCode(), isTerminating());
    }
}
