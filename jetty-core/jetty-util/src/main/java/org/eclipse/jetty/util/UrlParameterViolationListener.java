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

package org.eclipse.jetty.util;

public interface UrlParameterViolationListener
{
    void onBadEncoding(String cause, boolean allowed);

    void onBadPrecent(String cause, boolean allowed);

    void onTruncatedEncoding(String cause, boolean allowed);

    UrlParameterViolationListener NOOP = new UrlParameterViolationListener()
    {
        @Override
        public void onBadEncoding(String cause, boolean allowed)
        {
            // do nothing
        }

        @Override
        public void onBadPrecent(String cause, boolean allowed)
        {
            // do nothing
        }

        @Override
        public void onTruncatedEncoding(String cause, boolean allowed)
        {
            // do nothing
        }
    };
}
