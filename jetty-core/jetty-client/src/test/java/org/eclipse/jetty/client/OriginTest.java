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

package org.eclipse.jetty.client;

import java.util.Locale;

import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

// Isolated because the default Locale is changed.
@Isolated
public class OriginTest
{
    @ParameterizedTest
    @ValueSource(strings = {"en-US", "ar-EG-u-nu-arab"})
    public void testAddressAsStringDoesNotDependOnDefaultLocale(String languageTag)
    {
        // The Arabic-Egyptian locale with Arabic digits does not format numbers using the ascii characters 0-9
        // but with other UTF characters instead. If the locale was used to format numbers,
        // the address would then be formatted as "example.com:٤٤٣".

        Locale defaultLocale = Locale.getDefault();
        String address;
        try
        {
            Locale.setDefault(Locale.forLanguageTag(languageTag));
            address = new Origin.Address("example.com", 443).asString();
        }
        finally
        {
            Locale.setDefault(defaultLocale);
        }
        assertThat(address, is("example.com:443"));
    }
}
