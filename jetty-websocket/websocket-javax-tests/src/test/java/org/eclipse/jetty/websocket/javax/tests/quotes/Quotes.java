//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.tests.quotes;

import java.util.ArrayList;
import java.util.List;

public class Quotes
{
    private String author;
    private List<String> quotes = new ArrayList<>();

    public void addQuote(String quote)
    {
        quotes.add(quote);
    }

    public String getAuthor()
    {
        return author;
    }

    public void setAuthor(String author)
    {
        this.author = author;
    }

    public List<String> getQuotes()
    {
        return quotes;
    }

    @Override
    public String toString()
    {
        return String.format("Quotes[%s,quotes.size=%d]", author, quotes.size());
    }
}
