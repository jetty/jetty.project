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

package org.eclipse.jetty.xml;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Stack;

import org.eclipse.jetty.util.StringUtil;

public class XmlAppendable
{
    private static final String SPACES = "                                                                 ";
    private final Appendable _out;
    private final int _indent;
    private final Stack<String> _tags = new Stack<>();
    private String _space = "";

    public XmlAppendable(OutputStream out) throws IOException
    {
        Charset utf8 = StandardCharsets.UTF_8;
        _out = new OutputStreamWriter(out, utf8);
        _indent = 2;
        _out.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
    }

    public XmlAppendable openTag(String tag, Map<String, String> attributes) throws IOException
    {
        _out.append(_space).append('<').append(tag);
        attributes(attributes);

        _out.append(">\n");
        _space = _space + SPACES.substring(0, _indent);
        _tags.push(tag);
        return this;
    }

    public XmlAppendable openTag(String tag) throws IOException
    {
        _out.append(_space).append('<').append(tag).append(">\n");
        _space = _space + SPACES.substring(0, _indent);
        _tags.push(tag);
        return this;
    }

    public XmlAppendable content(String s) throws IOException
    {
        if (s != null)
            _out.append(StringUtil.sanitizeXmlString(s));

        return this;
    }

    public XmlAppendable cdata(String s) throws IOException
    {
        _out.append("<![CDATA[").append(s).append("]]>");
        return this;
    }

    public XmlAppendable tag(String tag) throws IOException
    {
        _out.append(_space).append('<').append(tag).append("/>\n");
        return this;
    }

    public XmlAppendable tag(String tag, Map<String, String> attributes) throws IOException
    {
        _out.append(_space).append('<').append(tag);
        attributes(attributes);
        _out.append("/>\n");
        return this;
    }

    public XmlAppendable tag(String tag, String content) throws IOException
    {
        _out.append(_space).append('<').append(tag).append('>');
        content(content);
        _out.append("</").append(tag).append(">\n");
        return this;
    }

    public XmlAppendable tag(String tag, Map<String, String> attributes, String content) throws IOException
    {
        _out.append(_space).append('<').append(tag);
        attributes(attributes);
        _out.append('>');
        content(content);
        _out.append("</").append(tag).append(">\n");
        return this;
    }

    // @checkstyle-disable-check : AbbreviationAsWordInNameCheck
    public XmlAppendable tagCDATA(String tag, String data) throws IOException
    {
        _out.append(_space).append('<').append(tag).append('>');
        cdata(data);
        _out.append("</").append(tag).append(">\n");
        return this;
    }
    // @checkstyle-enable-check : AbbreviationAsWordInNameCheck

    public XmlAppendable closeTag() throws IOException
    {
        if (_tags.isEmpty())
            throw new IllegalStateException("Tags closed");
        String tag = _tags.pop();
        _space = _space.substring(0, _space.length() - _indent);
        _out.append(_space).append("</").append(tag).append(">\n");
        if (_tags.isEmpty() && _out instanceof Closeable)
            ((Closeable)_out).close();
        return this;
    }

    private void attributes(Map<String, String> attributes) throws IOException
    {
        for (Map.Entry<String, String> entry : attributes.entrySet())
        {
            _out.append(' ').append(entry.getKey()).append("=\"");
            content(entry.getValue());
            _out.append('"');
        }
    }

    public void literal(String xml) throws IOException
    {
        _out.append(xml);
    }
}
