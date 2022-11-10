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

package org.eclipse.jetty.rewrite;

import java.io.IOException;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.rewrite.handler.RuleContainer;
import org.eclipse.jetty.server.HttpConfiguration.Customizer;
import org.eclipse.jetty.server.Request;

/**
 * <p>A {@link RuleContainer} that is an {@link org.eclipse.jetty.server.HttpConfiguration.Customizer},
 * so that its rules are processed at request customization time.</p>
 *
 * @see org.eclipse.jetty.server.HttpConfiguration#addCustomizer(Customizer)
 */
public class RewriteCustomizer extends RuleContainer implements Customizer
{
    @Override
    public Request customize(Request request, HttpFields.Mutable responseHeaders)
    {
        try
        {
            // TODO: rule are able to complete the request/response, but customizers cannot.
            RequestProcessor input = new RequestProcessor(request);
            return matchAndApply(input);
        }
        catch (IOException e)
        {
            throw new RuntimeIOException(e);
        }
    }
}
