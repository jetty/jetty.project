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

package org.eclipse.jetty.util.ajax;

import java.io.Externalizable;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>JSON parser and generator.</p>
 * <p>This class provides methods to convert POJOs to and from JSON notation.</p>
 * <p>The mapping from JSON to Java is:</p>
 *
 * <pre>
 *   object --&gt; Map&lt;String, Object&gt;
 *   array  --&gt; Object[]
 *   number --&gt; Double or Long
 *   string --&gt; String
 *   null   --&gt; null
 *   bool   --&gt; Boolean
 * </pre>
 *
 * <p>The Java to JSON mapping is:</p>
 *
 * <pre>
 *   String --&gt; string
 *   Number --&gt; number
 *   Map    --&gt; object
 *   List   --&gt; array
 *   Array  --&gt; array
 *   null   --&gt; null
 *   Boolean--&gt; boolean
 *   Object --&gt; string (dubious!)
 * </pre>
 *
 * <p>The interface {@link JSON.Convertible} may be implemented by classes that
 * wish to externalize and initialize specific fields to and from JSON objects.
 * Only directed acyclic graphs of objects are supported.</p>
 * <p>The interface {@link JSON.Generator} may be implemented by classes that
 * know how to render themselves as JSON and the {@link #toJSON(Object)} method
 * will use {@link JSON.Generator#addJSON(Appendable)} to generate the JSON.</p>
 * <p>The class {@link JSON.Literal} may be used to hold pre-generated JSON object.</p>
 * <p>The interface {@link JSON.Convertor} may be implemented to provide
 * converters for objects that may be registered with
 * {@link #addConvertor(Class, Convertor)}.
 * These converters are looked up by class, interface and super class by
 * {@link #getConvertor(Class)}.</p>
 * <p>If a JSON object has a {@code class} field, then a Java class for that
 * name is loaded and the method {@link #convertTo(Class, Map)} is used to find
 * a {@link JSON.Convertor} for that class.</p>
 * <p>If a JSON object has a {@code x-class} field then a direct lookup for a
 * {@link JSON.Convertor} for that class name is done (without loading the class).</p>
 */
public class JSON
{
    static final Logger LOG = LoggerFactory.getLogger(JSON.class);

    private final Map<String, Convertor> _convertors = new ConcurrentHashMap<>();
    private int _stringBufferSize = 1024;
    private Function<List<?>, Object> _arrayConverter = this::defaultArrayConverter;

    /**
     * @return the initial stringBuffer size to use when creating JSON strings
     * (default 1024)
     */
    public int getStringBufferSize()
    {
        return _stringBufferSize;
    }

    /**
     * @param stringBufferSize the initial stringBuffer size to use when creating JSON
     * strings (default 1024)
     */
    public void setStringBufferSize(int stringBufferSize)
    {
        _stringBufferSize = stringBufferSize;
    }

    private void quotedEscape(Appendable buffer, String input)
    {
        try
        {
            buffer.append('"');
            if (input != null && !input.isEmpty())
                escapeString(buffer, input);
            buffer.append('"');
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * <p>Escapes the characters of the given {@code input} string into the given buffer.</p>
     *
     * @param buffer the buffer to escape the string into
     * @param input the string to escape
     * @throws IOException if appending to the buffer fails
     * @see #escapeUnicode(Appendable, char)
     */
    public void escapeString(Appendable buffer, String input) throws IOException
    {
        // Default escaping algorithm.
        for (int i = 0; i < input.length(); ++i)
        {
            char c = input.charAt(i);

            // ASCII printable range
            if ((c >= 0x20) && (c <= 0x7E))
            {
                // Special cases for quotation-mark, reverse-solidus, and solidus.
                if ((c == '"') || (c == '\\')
                  /* solidus is optional - per Carsten Bormann (IETF)
                     || (c == '/') */)
                {
                    buffer.append('\\').append(c);
                }
                else
                {
                    // ASCII printable (that isn't escaped above).
                    buffer.append(c);
                }
            }
            else
            {
                // All other characters are escaped (in some way).
                // First we deal with the special short-form escaping.
                if (c == '\b') // backspace
                    buffer.append("\\b");
                else if (c == '\f') // form-feed
                    buffer.append("\\f");
                else if (c == '\n') // line feed
                    buffer.append("\\n");
                else if (c == '\r') // carriage return
                    buffer.append("\\r");
                else if (c == '\t') // tab
                    buffer.append("\\t");
                else if (c < 0x20 || c == 0x7F) // all control characters
                {
                    // Default behavior is to encode.
                    buffer.append(String.format("\\u%04x", (int)c));
                }
                else
                {
                    // Optional behavior in JSON spec.
                    escapeUnicode(buffer, c);
                }
            }
        }
    }

    /**
     * <p>Per JSON specification, unicode characters are by default NOT escaped.</p>
     * <p>Overriding this method allows for alternate behavior to escape those
     * with your choice of encoding.</p>
     *
     * <pre>
     * protected void escapeUnicode(Appendable buffer, char c) throws IOException
     * {
     *     // Unicode is backslash-u escaped
     *     buffer.append(String.format("\\u%04x", (int)c));
     * }
     * </pre>
     */
    protected void escapeUnicode(Appendable buffer, char c) throws IOException
    {
        buffer.append(c);
    }

    /**
     * <p>Converts any object to JSON.</p>
     *
     * @param object the object to convert
     * @return the JSON string representation of the object
     * @see #append(Appendable, Object)
     */
    public String toJSON(Object object)
    {
        StringBuilder buffer = new StringBuilder(getStringBufferSize());
        append(buffer, object);
        return buffer.toString();
    }

    /**
     * <p>Appends the given object as JSON to string buffer.</p>
     * <p>This method tests the given object type and calls other
     * appends methods for each object type, see for example
     * {@link #appendMap(Appendable, Map)}.</p>
     *
     * @param buffer the buffer to append to
     * @param object the object to convert to JSON
     */
    public void append(Appendable buffer, Object object)
    {
        try
        {
            if (object == null)
            {
                buffer.append("null");
            }
            // Most likely first
            else if (object instanceof Map)
            {
                appendMap(buffer, (Map<?, ?>)object);
            }
            else if (object instanceof String)
            {
                appendString(buffer, (String)object);
            }
            else if (object instanceof Number)
            {
                appendNumber(buffer, (Number)object);
            }
            else if (object instanceof Boolean)
            {
                appendBoolean(buffer, (Boolean)object);
            }
            else if (object.getClass().isArray())
            {
                appendArray(buffer, object);
            }
            else if (object instanceof Character)
            {
                appendString(buffer, object.toString());
            }
            else if (object instanceof Convertible)
            {
                appendJSON(buffer, (Convertible)object);
            }
            else if (object instanceof Generator)
            {
                appendJSON(buffer, (Generator)object);
            }
            else
            {
                // Check Convertor before Collection to support JSONCollectionConvertor.
                Convertor convertor = getConvertor(object.getClass());
                if (convertor != null)
                {
                    appendJSON(buffer, convertor, object);
                }
                else if (object instanceof Collection)
                {
                    appendArray(buffer, (Collection<?>)object);
                }
                else
                {
                    appendString(buffer, object.toString());
                }
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void appendNull(Appendable buffer)
    {
        try
        {
            buffer.append("null");
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void appendJSON(Appendable buffer, Convertor convertor, Object object)
    {
        appendJSON(buffer, new Convertible()
        {
            @Override
            public void fromJSON(Map<String, Object> object)
            {
            }

            @Override
            public void toJSON(Output out)
            {
                convertor.toJSON(object, out);
            }
        });
    }

    public void appendJSON(Appendable buffer, Convertible converter)
    {
        ConvertableOutput out = new ConvertableOutput(buffer);
        converter.toJSON(out);
        out.complete();
    }

    public void appendJSON(Appendable buffer, Generator generator)
    {
        generator.addJSON(buffer);
    }

    public void appendMap(Appendable buffer, Map<?, ?> map)
    {
        try
        {
            if (map == null)
            {
                appendNull(buffer);
                return;
            }

            buffer.append('{');
            Iterator<? extends Map.Entry<?, ?>> iter = map.entrySet().iterator();
            while (iter.hasNext())
            {
                Map.Entry<?, ?> entry = iter.next();
                quotedEscape(buffer, entry.getKey().toString());
                buffer.append(':');
                append(buffer, entry.getValue());
                if (iter.hasNext())
                    buffer.append(',');
            }
            buffer.append('}');
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void appendArray(Appendable buffer, Collection<?> collection)
    {
        try
        {
            if (collection == null)
            {
                appendNull(buffer);
                return;
            }

            buffer.append('[');
            Iterator<?> iter = collection.iterator();
            while (iter.hasNext())
            {
                append(buffer, iter.next());
                if (iter.hasNext())
                    buffer.append(',');
            }
            buffer.append(']');
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void appendArray(Appendable buffer, Object array)
    {
        try
        {
            if (array == null)
            {
                appendNull(buffer);
                return;
            }

            buffer.append('[');
            int length = Array.getLength(array);
            for (int i = 0; i < length; i++)
            {
                if (i != 0)
                    buffer.append(',');
                append(buffer, Array.get(array, i));
            }
            buffer.append(']');
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void appendBoolean(Appendable buffer, Boolean b)
    {
        try
        {
            if (b == null)
            {
                appendNull(buffer);
                return;
            }
            buffer.append(b ? "true" : "false");
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void appendNumber(Appendable buffer, Number number)
    {
        try
        {
            if (number == null)
            {
                appendNull(buffer);
                return;
            }
            buffer.append(String.valueOf(number));
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void appendString(Appendable buffer, String string)
    {
        if (string == null)
        {
            appendNull(buffer);
            return;
        }
        quotedEscape(buffer, string);
    }

    /**
     * <p>Factory method that creates a Map when a JSON representation of {@code {...}} is parsed.</p>
     *
     * @return a new Map representing the JSON object
     */
    protected Map<String, Object> newMap()
    {
        return new HashMap<>();
    }

    /**
     * <p>Factory method that creates an array when a JSON representation of {@code [...]} is parsed.</p>
     *
     * @param size the size of the array
     * @return a new array representing the JSON array
     * @deprecated use {@link #setArrayConverter(Function)} instead.
     */
    @Deprecated
    protected Object[] newArray(int size)
    {
        return new Object[size];
    }

    /**
     * <p>Every time a JSON array representation {@code [...]} is parsed, this method is called
     * to (possibly) return a different JSON instance (for example configured with different
     * converters) to parse the array items.</p>
     *
     * @return a JSON instance to parse array items
     */
    protected JSON contextForArray()
    {
        return this;
    }

    /**
     * <p>Every time a JSON object field representation {@code {"name": value}} is parsed,
     * this method is called to (possibly) return a different JSON instance (for example
     * configured with different converters) to parse the object field.</p>
     *
     * @param field the field name
     * @return a JSON instance to parse the object field
     */
    protected JSON contextFor(String field)
    {
        return this;
    }

    protected Object convertTo(Class<?> type, Map<String, Object> map)
    {
        if (Convertible.class.isAssignableFrom(type))
        {
            try
            {
                Convertible convertible = (Convertible)type.getConstructor().newInstance();
                convertible.fromJSON(map);
                return convertible;
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
        else
        {
            Convertor convertor = getConvertor(type);
            if (convertor != null)
                return convertor.fromJSON(map);
            return map;
        }
    }

    /**
     * <p>Registers a {@link Convertor} for the given class.</p>
     *
     * @param forClass the class the convertor applies to
     * @param convertor the convertor for the class
     */
    public void addConvertor(Class<?> forClass, Convertor convertor)
    {
        addConvertorFor(forClass.getName(), convertor);
    }

    /**
     * <p>Registers a {@link JSON.Convertor} for a named class.</p>
     *
     * @param name the name of the class the convertor applies to
     * @param convertor the convertor for the class
     */
    public void addConvertorFor(String name, Convertor convertor)
    {
        _convertors.put(name, convertor);
    }

    /**
     * <p>Unregisters a {@link Convertor} for a class.</p>
     *
     * @param forClass the class the convertor applies to
     * @return the convertor for the class
     */
    public Convertor removeConvertor(Class<?> forClass)
    {
        return removeConvertorFor(forClass.getName());
    }

    /**
     * <p>Unregisters a {@link Convertor} for a named class.</p>
     *
     * @param name the name of the class the convertor applies to
     * @return the convertor for the class
     */
    public Convertor removeConvertorFor(String name)
    {
        return _convertors.remove(name);
    }

    /**
     * <p>Looks up a convertor for a class.</p>
     * <p>If no match is found for the class, then the interfaces
     * for the class are tried.
     * If still no match is found, then the super class and its
     * interfaces are tried iteratively.</p>
     *
     * @param forClass the class to look up the convertor
     * @return a {@link JSON.Convertor} or null if none was found for the class
     */
    protected Convertor getConvertor(Class<?> forClass)
    {
        Class<?> cls = forClass;
        while (cls != null)
        {
            Convertor convertor = _convertors.get(cls.getName());
            if (convertor != null)
                return convertor;
            Class<?>[] intfs = cls.getInterfaces();
            for (Class<?> intf : intfs)
            {
                convertor = _convertors.get(intf.getName());
                if (convertor != null)
                    return convertor;
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /**
     * <p>Looks up a convertor for a class name.</p>
     *
     * @param name name of the class to look up the convertor
     * @return a {@link JSON.Convertor} or null if none were found.
     */
    public Convertor getConvertorFor(String name)
    {
        return _convertors.get(name);
    }

    /**
     * @return the function to customize the Java representation of JSON arrays
     * @see #setArrayConverter(Function)
     */
    public Function<List<?>, Object> getArrayConverter()
    {
        return _arrayConverter;
    }

    /**
     * <p>Sets the function to convert JSON arrays from their default Java
     * representation, a {@code List<Object>}, to another Java data structure
     * such as an {@code Object[]}.</p>
     *
     * @param arrayConverter the function to customize the Java representation of JSON arrays
     * @see #getArrayConverter()
     */
    public void setArrayConverter(Function<List<?>, Object> arrayConverter)
    {
        _arrayConverter = Objects.requireNonNull(arrayConverter);
    }

    /**
     * <p>Parses the given JSON source into an object.</p>
     * <p>Although the JSON specification does not allow comments (of any kind)
     * this method optionally strips out outer comments of this form:</p>
     * <pre>
     * // An outer comment line.
     * /&#42; Another outer comment, multiline.
     * // Yet another comment line.
     * {
     *     "name": "the real JSON"
     * }
     * &#42;/ End of outer comment, multiline.
     * </pre>
     *
     * @param source the JSON source to parse
     * @param stripOuterComment whether to strip outer comments
     * @return the object constructed from the JSON string representation
     */
    public Object parse(Source source, boolean stripOuterComment)
    {
        int commentState = 0; // 0=no comment, 1="/", 2="/*", 3="/* *" -1="//"
        if (!stripOuterComment)
            return parse(source);

        int stripState = 1; // 0=no strip, 1=wait for /*, 2= wait for */

        Object o = null;
        while (source.hasNext())
        {
            char c = source.peek();

            // Handle // or /* comment.
            if (commentState == 1)
            {
                switch (c)
                {
                    case '/':
                        commentState = -1;
                        break;
                    case '*':
                        commentState = 2;
                        if (stripState == 1)
                        {
                            commentState = 0;
                            stripState = 2;
                        }
                        break;
                    default:
                        break;
                }
            }
            // Handle /* C style */ comment.
            else if (commentState > 1)
            {
                switch (c)
                {
                    case '*':
                        commentState = 3;
                        break;
                    case '/':
                        if (commentState == 3)
                        {
                            commentState = 0;
                            if (stripState == 2)
                                return o;
                        }
                        break;
                    default:
                        commentState = 2;
                        break;
                }
            }
            // Handle // comment.
            else if (commentState < 0)
            {
                switch (c)
                {
                    case '\r':
                    case '\n':
                        commentState = 0;
                        break;
                    default:
                        break;
                }
            }
            // Handle unknown.
            else
            {
                if (!Character.isWhitespace(c))
                {
                    if (c == '/')
                        commentState = 1;
                    else if (c == '*')
                        commentState = 3;
                    else if (o == null)
                    {
                        o = parse(source);
                        continue;
                    }
                }
            }

            source.next();
        }

        return o;
    }

    /**
     * <p>Parses the given JSON string into an object.</p>
     *
     * @param string the JSON string to parse
     * @return the object constructed from the JSON string representation
     */
    public Object fromJSON(String string)
    {
        return parse(new StringSource(string), false);
    }

    /**
     * <p>Parses the JSON from the given Reader into an object.</p>
     *
     * @param reader the Reader to read the JSON from
     * @return the object constructed from the JSON string representation
     */
    public Object fromJSON(Reader reader)
    {
        return parse(new ReaderSource(reader), false);
    }

    /**
     * <p>Parses the given JSON source into an object.</p>
     * <p>Although the JSON specification does not allow comments (of any kind)
     * this method strips out initial comments of this form:</p>
     * <pre>
     * // An initial comment line.
     * /&#42; An initial
     *    multiline comment &#42;/
     * {
     *     "name": "foo"
     * }
     * </pre>
     * <p>This method detects the object type and calls other
     * parse methods for each object type, see for example
     * {@link #parseArray(Source)}.</p>
     *
     * @param source the JSON source to parse
     * @return the object constructed from the JSON string representation
     */
    public Object parse(Source source)
    {
        int commentState = 0; // 0=no comment, 1="/", 2="/*", 3="/* *" -1="//"

        while (source.hasNext())
        {
            char c = source.peek();

            // Handle // or /* comment.
            if (commentState == 1)
            {
                switch (c)
                {
                    case '/':
                        commentState = -1;
                        break;
                    case '*':
                        commentState = 2;
                        break;
                    default:
                        break;
                }
            }
            // Handle /* C Style */ comment.
            else if (commentState > 1)
            {
                switch (c)
                {
                    case '*':
                        commentState = 3;
                        break;
                    case '/':
                        if (commentState == 3)
                            commentState = 0;
                        break;
                    default:
                        commentState = 2;
                        break;
                }
            }
            // Handle // comment.
            else if (commentState < 0)
            {
                switch (c)
                {
                    case '\r':
                    case '\n':
                        commentState = 0;
                        break;
                    default:
                        break;
                }
            }
            // Handle unknown.
            else
            {
                switch (c)
                {
                    case '{':
                        return parseObject(source);
                    case '[':
                        return parseArray(source);
                    case '"':
                        return parseString(source);
                    case '-':
                        return parseNumber(source);
                    case 'n':
                        complete("null", source);
                        return null;
                    case 't':
                        complete("true", source);
                        return Boolean.TRUE;
                    case 'f':
                        complete("false", source);
                        return Boolean.FALSE;
                    case 'u':
                        complete("undefined", source);
                        return null;
                    case 'N':
                        complete("NaN", source);
                        return null;
                    case '/':
                        commentState = 1;
                        break;
                    default:
                        if (Character.isDigit(c))
                            return parseNumber(source);
                        else if (Character.isWhitespace(c))
                            break;
                        return handleUnknown(source, c);
                }
            }
            source.next();
        }

        return null;
    }

    protected Object handleUnknown(Source source, char c)
    {
        throw new IllegalStateException("unknown char '" + c + "'(" + (int)c + ") in " + source);
    }

    protected Object parseObject(Source source)
    {
        if (source.next() != '{')
            throw new IllegalStateException();

        Map<String, Object> map = newMap();
        char next = seekTo("\"}", source);
        while (source.hasNext())
        {
            if (next == '}')
            {
                source.next();
                break;
            }

            String name = parseString(source);
            seekTo(':', source);
            source.next();

            Object value = contextFor(name).parse(source);
            map.put(name, value);

            seekTo(",}", source);
            next = source.next();
            if (next == '}')
                break;
            else
                next = seekTo("\"}", source);
        }

        String xclassname = (String)map.get("x-class");
        if (xclassname != null)
        {
            Convertor c = getConvertorFor(xclassname);
            if (c != null)
                return c.fromJSON(map);
            LOG.warn("No Convertor for x-class '{}'", xclassname);
        }

        String classname = (String)map.get("class");
        if (classname != null)
        {
            try
            {
                Class<?> c = Loader.loadClass(classname);
                return convertTo(c, map);
            }
            catch (ClassNotFoundException e)
            {
                LOG.warn("No class for '{}'", classname);
            }
        }

        return map;
    }

    private Object defaultArrayConverter(List<?> list)
    {
        // Call newArray() to keep backward compatibility.
        Object[] objects = newArray(list.size());
        IntStream.range(0, list.size()).forEach(i -> objects[i] = list.get(i));
        return objects;
    }

    protected Object parseArray(Source source)
    {
        if (source.next() != '[')
            throw new IllegalStateException();

        int size = 0;
        List<Object> list = null;
        Object item = null;
        boolean comma = true;
        while (source.hasNext())
        {
            char c = source.peek();
            switch (c)
            {
                case ']':
                    source.next();
                    switch (size)
                    {
                        case 0:
                            list = Collections.emptyList();
                            break;
                        case 1:
                            list = Collections.singletonList(item);
                            break;
                        default:
                            break;
                    }
                    return getArrayConverter().apply(list);

                case ',':
                    if (comma)
                        throw new IllegalStateException();
                    comma = true;
                    source.next();
                    break;

                default:
                    if (Character.isWhitespace(c))
                    {
                        source.next();
                    }
                    else
                    {
                        comma = false;
                        if (size++ == 0)
                        {
                            item = contextForArray().parse(source);
                        }
                        else if (list == null)
                        {
                            list = new ArrayList<>();
                            list.add(item);
                            item = contextForArray().parse(source);
                            list.add(item);
                            item = null;
                        }
                        else
                        {
                            item = contextForArray().parse(source);
                            list.add(item);
                            item = null;
                        }
                    }
                    break;
            }
        }

        throw new IllegalStateException("unexpected end of array");
    }

    protected String parseString(Source source)
    {
        if (source.next() != '"')
            throw new IllegalStateException();

        boolean escape = false;
        StringBuilder b = null;
        char[] scratch = source.scratchBuffer();
        if (scratch != null)
        {
            int i = 0;
            while (source.hasNext())
            {
                if (i >= scratch.length)
                {
                    // We have filled the scratch buffer, so we must
                    // use the StringBuffer for a large string.
                    b = new StringBuilder(scratch.length * 2);
                    b.append(scratch, 0, i);
                    break;
                }

                char c = source.next();

                if (escape)
                {
                    escape = false;
                    switch (c)
                    {
                        case '"':
                            scratch[i++] = '"';
                            break;
                        case '\\':
                            scratch[i++] = '\\';
                            break;
                        case '/':
                            scratch[i++] = '/';
                            break;
                        case 'b':
                            scratch[i++] = '\b';
                            break;
                        case 'f':
                            scratch[i++] = '\f';
                            break;
                        case 'n':
                            scratch[i++] = '\n';
                            break;
                        case 'r':
                            scratch[i++] = '\r';
                            break;
                        case 't':
                            scratch[i++] = '\t';
                            break;
                        case 'u':
                            char uc = (char)((TypeUtil.convertHexDigit((byte)source.next()) << 12) + (TypeUtil.convertHexDigit((byte)source.next()) << 8) +
                                (TypeUtil.convertHexDigit((byte)source.next()) << 4) + (TypeUtil.convertHexDigit((byte)source.next())));
                            scratch[i++] = uc;
                            break;
                        default:
                            scratch[i++] = c;
                    }
                }
                else if (c == '\\')
                {
                    escape = true;
                }
                else if (c == '\"')
                {
                    // Return string that fits within scratch buffer
                    return new String(scratch, 0, i);
                }
                else
                {
                    scratch[i++] = c;
                }
            }

            // Missing end quote, but return string anyway ?
            if (b == null)
                return new String(scratch, 0, i);
        }
        else
        {
            b = new StringBuilder(getStringBufferSize());
        }

        // parse large string into string buffer
        StringBuilder builder = b;
        while (source.hasNext())
        {
            char c = source.next();
            if (escape)
            {
                escape = false;
                switch (c)
                {
                    case '"':
                        builder.append('"');
                        break;
                    case '\\':
                        builder.append('\\');
                        break;
                    case '/':
                        builder.append('/');
                        break;
                    case 'b':
                        builder.append('\b');
                        break;
                    case 'f':
                        builder.append('\f');
                        break;
                    case 'n':
                        builder.append('\n');
                        break;
                    case 'r':
                        builder.append('\r');
                        break;
                    case 't':
                        builder.append('\t');
                        break;
                    case 'u':
                        char uc = (char)((TypeUtil.convertHexDigit((byte)source.next()) << 12) + (TypeUtil.convertHexDigit((byte)source.next()) << 8) +
                            (TypeUtil.convertHexDigit((byte)source.next()) << 4) + (TypeUtil.convertHexDigit((byte)source.next())));
                        builder.append(uc);
                        break;
                    default:
                        builder.append(c);
                }
            }
            else if (c == '\\')
            {
                escape = true;
            }
            else if (c == '\"')
            {
                break;
            }
            else
            {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    public Number parseNumber(Source source)
    {
        boolean minus = false;
        long number = 0;
        StringBuilder buffer = null;

        longLoop:
        while (source.hasNext())
        {
            char c = source.peek();
            switch (c)
            {
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    number = number * 10 + (c - '0');
                    source.next();
                    break;
                case '-':
                case '+':
                    if (number != 0)
                        throw new IllegalStateException("bad number");
                    minus = true;
                    source.next();
                    break;
                case '.':
                case 'e':
                case 'E':
                    buffer = new StringBuilder(16);
                    if (minus)
                        buffer.append('-');
                    buffer.append(number);
                    buffer.append(c);
                    source.next();
                    break longLoop;
                default:
                    break longLoop;
            }
        }

        if (buffer == null)
            return minus ? -1 * number : number;

        doubleLoop:
        while (source.hasNext())
        {
            char c = source.peek();
            switch (c)
            {
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                case '-':
                case '.':
                case '+':
                case 'e':
                case 'E':
                    buffer.append(c);
                    source.next();
                    break;
                default:
                    break doubleLoop;
            }
        }
        return Double.valueOf(buffer.toString());
    }

    protected void seekTo(char seek, Source source)
    {
        while (source.hasNext())
        {
            char c = source.peek();
            if (c == seek)
                return;

            if (!Character.isWhitespace(c))
                throw new IllegalStateException("Unexpected '" + c + " while seeking '" + seek + "'");
            source.next();
        }

        throw new IllegalStateException("Expected '" + seek + "'");
    }

    protected char seekTo(String seek, Source source)
    {
        while (source.hasNext())
        {
            char c = source.peek();
            if (seek.indexOf(c) >= 0)
                return c;

            if (!Character.isWhitespace(c))
                throw new IllegalStateException("Unexpected '" + c + "' while seeking one of '" + seek + "'");
            source.next();
        }

        throw new IllegalStateException("Expected one of '" + seek + "'");
    }

    protected static void complete(String seek, Source source)
    {
        int i = 0;
        while (source.hasNext() && i < seek.length())
        {
            char c = source.next();
            if (c != seek.charAt(i++))
                throw new IllegalStateException("Unexpected '" + c + " while seeking  \"" + seek + "\"");
        }

        if (i < seek.length())
            throw new IllegalStateException("Expected \"" + seek + "\"");
    }

    private final class ConvertableOutput implements Output
    {
        private final Appendable _buffer;
        private char c = '{';

        private ConvertableOutput(Appendable buffer)
        {
            _buffer = buffer;
        }

        public void complete()
        {
            try
            {
                if (c == '{')
                    _buffer.append("{}");
                else if (c != 0)
                    _buffer.append("}");
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void add(Object obj)
        {
            if (c == 0)
                throw new IllegalStateException();
            append(_buffer, obj);
            c = 0;
        }

        @Override
        public void add(String name, Object value)
        {
            try
            {
                if (c == 0)
                    throw new IllegalStateException();
                _buffer.append(c);
                quotedEscape(_buffer, name);
                _buffer.append(':');
                append(_buffer, value);
                c = ',';
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void add(String name, double value)
        {
            try
            {
                if (c == 0)
                    throw new IllegalStateException();
                _buffer.append(c);
                quotedEscape(_buffer, name);
                _buffer.append(':');
                appendNumber(_buffer, value);
                c = ',';
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void add(String name, long value)
        {
            try
            {
                if (c == 0)
                    throw new IllegalStateException();
                _buffer.append(c);
                quotedEscape(_buffer, name);
                _buffer.append(':');
                appendNumber(_buffer, value);
                c = ',';
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void add(String name, boolean value)
        {
            try
            {
                if (c == 0)
                    throw new IllegalStateException();
                _buffer.append(c);
                quotedEscape(_buffer, name);
                _buffer.append(':');
                appendBoolean(_buffer, value ? Boolean.TRUE : Boolean.FALSE);
                c = ',';
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void addClass(Class<?> type)
        {
            try
            {
                if (c == 0)
                    throw new IllegalStateException();
                _buffer.append(c);
                _buffer.append("\"class\":");
                append(_buffer, type.getName());
                c = ',';
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * <p>A generic source for a JSON representation.</p>
     */
    public interface Source
    {
        boolean hasNext();

        char next();

        char peek();

        char[] scratchBuffer();
    }

    /**
     * <p>An in-memory source for a JSON string.</p>
     */
    public static class StringSource implements Source
    {
        private final String string;
        private int index;
        private char[] scratch;

        public StringSource(String s)
        {
            string = s;
        }

        @Override
        public boolean hasNext()
        {
            if (index < string.length())
                return true;
            scratch = null;
            return false;
        }

        @Override
        public char next()
        {
            return string.charAt(index++);
        }

        @Override
        public char peek()
        {
            return string.charAt(index);
        }

        @Override
        public String toString()
        {
            return string.substring(0, index) + "|||" + string.substring(index);
        }

        @Override
        public char[] scratchBuffer()
        {
            if (scratch == null)
                scratch = new char[string.length()];
            return scratch;
        }
    }

    /**
     * <p>A Reader source for a JSON string.</p>
     */
    public static class ReaderSource implements Source
    {
        private Reader _reader;
        private int _next = -1;
        private char[] scratch;

        public ReaderSource(Reader r)
        {
            _reader = r;
        }

        public void setReader(Reader reader)
        {
            _reader = reader;
            _next = -1;
        }

        @Override
        public boolean hasNext()
        {
            getNext();
            if (_next < 0)
            {
                scratch = null;
                return false;
            }
            return true;
        }

        @Override
        public char next()
        {
            getNext();
            char c = (char)_next;
            _next = -1;
            return c;
        }

        @Override
        public char peek()
        {
            getNext();
            return (char)_next;
        }

        private void getNext()
        {
            if (_next < 0)
            {
                try
                {
                    _next = _reader.read();
                }
                catch (IOException e)
                {
                    throw new RuntimeException(e);
                }
            }
        }

        @Override
        public char[] scratchBuffer()
        {
            if (scratch == null)
                scratch = new char[1024];
            return scratch;
        }
    }

    /**
     * JSON Output class for use by {@link Convertible}.
     */
    public interface Output
    {
        public void addClass(Class<?> c);

        public void add(Object obj);

        public void add(String name, Object value);

        public void add(String name, double value);

        public void add(String name, long value);

        public void add(String name, boolean value);
    }

    /**
     * <p>JSON Convertible object.</p>
     * <p>Classes can implement this interface in a similar way to the
     * {@link Externalizable} interface is used to allow classes to
     * provide their own serialization mechanism.</p>
     * <p>A JSON.Convertible object may be written to a JSONObject or
     * initialized from a Map of field names to values.</p>
     * <p>If the JSON is to be convertible back to an Object, then the method
     * {@link Output#addClass(Class)} must be called from within
     * {@link #toJSON(Output)}.</p>
     */
    public interface Convertible
    {
        public void toJSON(Output out);

        public void fromJSON(Map<String, Object> object);
    }

    /**
     * <p>JSON Convertor.</p>
     * <p>Implementations provide convertors for objects that may be
     * registered with {@link #addConvertor(Class, Convertor)}.
     * These convertors are looked up by class, interfaces and super class
     * by {@link JSON#getConvertor(Class)}.
     * Convertors should be used when the classes to be converted cannot
     * implement {@link Convertible} or {@link Generator}.</p>
     */
    public interface Convertor
    {
        public void toJSON(Object obj, Output out);

        public Object fromJSON(Map<String, Object> object);
    }

    /**
     * <p>JSON Generator.</p>
     * <p>Implemented by classes that can add their own JSON representation
     * directly to a StringBuffer.
     * This is useful for object instances that are frequently
     * converted and wish to avoid multiple conversions, as the result of
     * the generation may be cached.</p>
     */
    public interface Generator
    {
        public void addJSON(Appendable buffer);
    }

    /**
     * <p>A Literal JSON generator.</p>
     * <p>A utility instance of {@link JSON.Generator}
     * that holds a pre-generated string on JSON text.</p>
     */
    public static class Literal implements Generator
    {
        private final String _json;

        /**
         * Constructs a literal JSON instance.
         *
         * @param json a literal JSON string
         */
        public Literal(String json)
        {
            _json = json;
        }

        @Override
        public String toString()
        {
            return _json;
        }

        @Override
        public void addJSON(Appendable buffer)
        {
            try
            {
                buffer.append(_json);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
    }
}
