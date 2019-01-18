//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.jupiter.api.Test;

public class HttpComplianceTest
{
    @Test
    public void testBuilder2616()
    {
        HttpCompliance compliance = HttpCompliance.rfc2616Builder().build();

        assertThat("compliance[rfc2616]", compliance.toString(), not(containsString("CUSTOM")));
        assertThat("compliance[rfc2616].allowHttp09", compliance.allowHttp09(), is(true));
    }

    @Test
    public void testBuilder7230()
    {
        HttpCompliance compliance = HttpCompliance.rfc7230Builder().build();

        assertThat("compliance[rfc7230].allowHttp09", compliance.allowHttp09(), is(false));
    }

    @Test
    public void testBuilderCustomize7230()
    {
        HttpCompliance compliance = HttpCompliance.rfc7230Builder()
                .requireColonAfterFieldName(false).build();
        assertThat("compliance[rfc7230]", compliance.toString(), containsString("CUSTOM"));
    }

    @Test
    public void testBuilderCustomizeManual()
    {
        HttpCompliance compliance = HttpCompliance.rfc7230Builder()
                .allowCaseInsensitiveFieldNames(true)
                .requireColonAfterFieldName(true)
                .allowHttp09(false)
                .allowMultipleContentLengths(true)
                .build();
        assertThat("compliance[rfc7230]", compliance.toString(), containsString("CUSTOM"));
    }
}
