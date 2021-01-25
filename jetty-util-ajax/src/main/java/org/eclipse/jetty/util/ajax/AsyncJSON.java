//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.util.ajax;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.util.ArrayTernaryTrie;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.Trie;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.ajax.JSON.Convertible;
import org.eclipse.jetty.util.ajax.JSON.Convertor;

/**
 * <p>A non-blocking JSON parser that can parse partial JSON strings.</p>
 * <p>Usage:</p>
 * <pre>
 * AsyncJSON parser = new AsyncJSON.Factory().newAsyncJSON();
 *
 * // Feed the parser with partial JSON string content.
 * parser.parse(chunk1);
 * parser.parse(chunk2);
 *
 * // Tell the parser that the JSON string content
 * // is terminated and get the JSON object back.
 * Map&lt;String, Object&gt; object = parser.complete();
 * </pre>
 * <p>After the call to {@link #complete()} the parser can be reused to parse
 * another JSON string.</p>
 * <p>Custom objects can be created by specifying a {@code "class"} or
 * {@code "x-class"} field:</p>
 * <pre>
 * String json = """
 * {
 *   "x-class": "com.acme.Person",
 *   "firstName": "John",
 *   "lastName": "Doe",
 *   "age": 42
 * }
 * """
 *
 * parser.parse(json);
 * com.acme.Person person = parser.complete();
 * </pre>
 * <p>Class {@code com.acme.Person} must either implement {@link Convertible},
 * or be mapped with a {@link Convertor} via {@link Factory#putConvertor(String, Convertor)}.</p>
 */
public class AsyncJSON
{
    /**
     * <p>The factory that creates AsyncJSON instances.</p>
     * <p>The factory can be configured with custom {@link Convertor}s,
     * and with cached strings that will not be allocated if they can
     * be looked up from the cache.</p>
     */
    public static class Factory
    {
        private Trie<CachedString> cache;
        private Map<String, Convertor> convertors;
        private boolean detailedParseException;

        /**
         * @return whether a parse failure should report the whole JSON string or just the last chunk
         */
        public boolean isDetailedParseException()
        {
            return detailedParseException;
        }

        /**
         * @param detailedParseException whether a parse failure should report the whole JSON string or just the last chunk
         */
        public void setDetailedParseException(boolean detailedParseException)
        {
            this.detailedParseException = detailedParseException;
        }

        /**
         * @param value the string to cache
         * @return whether the value can be cached
         */
        public boolean cache(String value)
        {
            if (cache == null)
                cache = new ArrayTernaryTrie.Growing<>(false, 64, 64);

            CachedString cached = new CachedString(value);
            if (cached.isCacheable())
            {
                cache.put(cached.encoded, cached);
                return true;
            }
            return false;
        }

        /**
         * <p>Attempts to return a cached string from the buffer bytes.</p>
         * <p>In case of a cache hit, the string is returned and the buffer
         * position updated.</p>
         * <p>In case of cache miss, {@code null} is returned and the buffer
         * position is left unaltered.</p>
         *
         * @param buffer the buffer to lookup the string from
         * @return a cached string or {@code null}
         */
        protected String cached(ByteBuffer buffer)
        {
            if (cache != null)
            {
                CachedString result = cache.getBest(buffer, 0, buffer.remaining());
                if (result != null)
                {
                    buffer.position(buffer.position() + result.encoded.length());
                    return result.value;
                }
            }
            return null;
        }

        /**
         * @return a new parser instance
         */
        public AsyncJSON newAsyncJSON()
        {
            return new AsyncJSON(this);
        }

        /**
         * <p>Associates the given {@link Convertor} to the given class name.</p>
         *
         * @param className the domain class name such as {@code com.acme.Person}
         * @param convertor the {@link Convertor} that converts {@code Map} to domain objects
         */
        public void putConvertor(String className, Convertor convertor)
        {
            if (convertors == null)
                convertors = new ConcurrentHashMap<>();
            convertors.put(className, convertor);
        }

        /**
         * <p>Removes the {@link Convertor} associated with the given class name.</p>
         *
         * @param className the class name associated with the {@link Convertor}
         * @return the {@link Convertor} associated with the class name, or {@code null}
         */
        public Convertor removeConvertor(String className)
        {
            if (convertors != null)
                return convertors.remove(className);
            return null;
        }

        /**
         * <p>Returns the {@link Convertor} associated with the given class name, if any.</p>
         *
         * @param className the class name associated with the {@link Convertor}
         * @return the {@link Convertor} associated with the class name, or {@code null}
         */
        public Convertor getConvertor(String className)
        {
            return convertors == null ? null : convertors.get(className);
        }

        private static class CachedString
        {
            private final String encoded;
            private final String value;

            private CachedString(String value)
            {
                this.encoded = JSON.toString(value);
                this.value = value;
            }

            private boolean isCacheable()
            {
                for (int i = encoded.length(); i-- > 0;)
                {
                    char c = encoded.charAt(i);
                    if (c > 127)
                        return false;
                }
                return true;
            }
        }
    }

    private static final Object UNSET = new Object();

    private final FrameStack stack = new FrameStack();
    private final NumberBuilder numberBuilder = new NumberBuilder();
    private final Utf8StringBuilder stringBuilder = new Utf8StringBuilder(32);
    private final Factory factory;
    private List<ByteBuffer> chunks;

    public AsyncJSON(Factory factory)
    {
        this.factory = factory;
    }

    // Used by tests only.
    boolean isEmpty()
    {
        return stack.isEmpty();
    }

    /**
     * <p>Feeds the parser with the given bytes chunk.</p>
     *
     * @param bytes the bytes to parse
     * @return whether the JSON parsing was complete
     * @throws IllegalArgumentException if the JSON is malformed
     */
    public boolean parse(byte[] bytes)
    {
        return parse(bytes, 0, bytes.length);
    }

    /**
     * <p>Feeds the parser with the given bytes chunk.</p>
     *
     * @param bytes the bytes to parse
     * @param offset the offset to start parsing from
     * @param length the number of bytes to parse
     * @return whether the JSON parsing was complete
     * @throws IllegalArgumentException if the JSON is malformed
     */
    public boolean parse(byte[] bytes, int offset, int length)
    {
        return parse(ByteBuffer.wrap(bytes, offset, length));
    }

    /**
     * <p>Feeds the parser with the given buffer chunk.</p>
     *
     * @param buffer the buffer to parse
     * @return whether the JSON parsing was complete
     * @throws IllegalArgumentException if the JSON is malformed
     */
    public boolean parse(ByteBuffer buffer)
    {
        try
        {
            if (factory.isDetailedParseException())
            {
                if (chunks == null)
                    chunks = new ArrayList<>();
                ByteBuffer copy = buffer.isDirect()
                    ? ByteBuffer.allocateDirect(buffer.remaining())
                    : ByteBuffer.allocate(buffer.remaining());
                copy.put(buffer).flip();
                chunks.add(copy);
                buffer.flip();
            }

            if (stack.isEmpty())
                stack.push(State.COMPLETE, UNSET);

            while (true)
            {
                Frame frame = stack.peek();
                State state = frame.state;
                switch (state)
                {
                    case COMPLETE:
                    {
                        if (frame.value == UNSET)
                        {
                            if (parseAny(buffer))
                                break;
                            return false;
                        }
                        else
                        {
                            while (buffer.hasRemaining())
                            {
                                int position = buffer.position();
                                byte peek = buffer.get(position);
                                if (isWhitespace(peek))
                                    buffer.position(position + 1);
                                else
                                    throw newInvalidJSON(buffer, "invalid character after JSON data");
                            }
                            return true;
                        }
                    }
                    case NULL:
                    {
                        if (parseNull(buffer))
                            break;
                        return false;
                    }
                    case TRUE:
                    {
                        if (parseTrue(buffer))
                            break;
                        return false;
                    }
                    case FALSE:
                    {
                        if (parseFalse(buffer))
                            break;
                        return false;
                    }
                    case NUMBER:
                    {
                        if (parseNumber(buffer))
                            break;
                        return false;
                    }
                    case STRING:
                    {
                        if (parseString(buffer))
                            break;
                        return false;
                    }
                    case ESCAPE:
                    {
                        if (parseEscape(buffer))
                            break;
                        return false;
                    }
                    case UNICODE:
                    {
                        if (parseUnicode(buffer))
                            break;
                        return false;
                    }
                    case ARRAY:
                    {
                        if (parseArray(buffer))
                            break;
                        return false;
                    }
                    case OBJECT:
                    {
                        if (parseObject(buffer))
                            break;
                        return false;
                    }
                    case OBJECT_FIELD:
                    {
                        if (parseObjectField(buffer))
                            break;
                        return false;
                    }
                    case OBJECT_FIELD_NAME:
                    {
                        if (parseObjectFieldName(buffer))
                            break;
                        return false;
                    }
                    case OBJECT_FIELD_VALUE:
                    {
                        if (parseObjectFieldValue(buffer))
                            break;
                        return false;
                    }
                    default:
                    {
                        throw new IllegalStateException("invalid state " + state);
                    }
                }
            }
        }
        catch (Throwable x)
        {
            reset();
            throw x;
        }
    }

    /**
     * <p>Signals to the parser that the parse data is complete, and returns
     * the object parsed from the JSON chunks passed to the {@code parse()}
     * methods.</p>
     *
     * @param <R> the type the result is cast to
     * @return the result of the JSON parsing
     * @throws IllegalArgumentException if the JSON is malformed
     * @throws IllegalStateException if the no JSON was passed to the {@code parse()} methods
     */
    public <R> R complete()
    {
        try
        {
            if (stack.isEmpty())
                throw new IllegalStateException("no JSON parsed");

            while (true)
            {
                State state = stack.peek().state;
                switch (state)
                {
                    case NUMBER:
                    {
                        Number value = numberBuilder.value();
                        stack.pop();
                        stack.peek().value(value);
                        break;
                    }
                    case COMPLETE:
                    {
                        if (stack.peek().value == UNSET)
                            throw new IllegalStateException("invalid state " + state);
                        return (R)end();
                    }
                    default:
                    {
                        throw newInvalidJSON(BufferUtil.EMPTY_BUFFER, "incomplete JSON");
                    }
                }
            }
        }
        catch (Throwable x)
        {
            reset();
            throw x;
        }
    }

    /**
     * <p>When a JSON <code>{</code> is encountered during parsing,
     * this method is called to create a new {@code Map} instance.</p>
     * <p>Subclasses may override to return a custom {@code Map} instance.</p>
     *
     * @param context the parsing context
     * @return a {@code Map} instance
     */
    protected Map<String, Object> newObject(Context context)
    {
        return new HashMap<>();
    }

    /**
     * <p>When a JSON <code>[</code> is encountered during parsing,
     * this method is called to create a new {@code List} instance.</p>
     * <p>Subclasses may override to return a custom {@code List} instance.</p>
     *
     * @param context the parsing context
     * @return a {@code List} instance
     */
    protected List<Object> newArray(Context context)
    {
        return new ArrayList<>();
    }

    private Object end()
    {
        Object result = stack.peek().value;
        reset();
        return result;
    }

    private void reset()
    {
        stack.clear();
        chunks = null;
    }

    private boolean parseAny(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            byte peek = buffer.get(buffer.position());
            switch (peek)
            {
                case '[':
                    if (parseArray(buffer))
                        return true;
                    break;
                case '{':
                    if (parseObject(buffer))
                        return true;
                    break;
                case '-':
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
                    if (parseNumber(buffer))
                        return true;
                    break;
                case '"':
                    if (parseString(buffer))
                        return true;
                    break;
                case 'f':
                    if (parseFalse(buffer))
                        return true;
                    break;
                case 'n':
                    if (parseNull(buffer))
                        return true;
                    break;
                case 't':
                    if (parseTrue(buffer))
                        return true;
                    break;
                default:
                    if (isWhitespace(peek))
                    {
                        buffer.get();
                        break;
                    }
                    throw newInvalidJSON(buffer, "unrecognized JSON value");
            }
        }
        return false;
    }

    private boolean parseNull(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            byte currentByte = buffer.get();
            switch (currentByte)
            {
                case 'n':
                    if (stack.peek().state != State.NULL)
                    {
                        stack.push(State.NULL, 0);
                        parseNullCharacter(buffer, 0);
                        break;
                    }
                    throw newInvalidJSON(buffer, "invalid 'null' literal");
                case 'u':
                    parseNullCharacter(buffer, 1);
                    break;
                case 'l':
                    int index = (Integer)stack.peek().value;
                    if (index == 2 || index == 3)
                        parseNullCharacter(buffer, index);
                    else
                        throw newInvalidJSON(buffer, "invalid 'null' literal");
                    if (index == 3)
                    {
                        stack.pop();
                        stack.peek().value(null);
                        return true;
                    }
                    break;
                default:
                    throw newInvalidJSON(buffer, "invalid 'null' literal");
            }
        }
        return false;
    }

    private void parseNullCharacter(ByteBuffer buffer, int index)
    {
        Frame frame = stack.peek();
        int value = (Integer)frame.value;
        if (value == index)
            frame.value = ++value;
        else
            throw newInvalidJSON(buffer, "invalid 'null' literal");
    }

    private boolean parseTrue(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            byte currentByte = buffer.get();
            switch (currentByte)
            {
                case 't':
                    if (stack.peek().state != State.TRUE)
                    {
                        stack.push(State.TRUE, 0);
                        parseTrueCharacter(buffer, 0);
                        break;
                    }
                    throw newInvalidJSON(buffer, "invalid 'true' literal");
                case 'r':
                    parseTrueCharacter(buffer, 1);
                    break;
                case 'u':
                    parseTrueCharacter(buffer, 2);
                    break;
                case 'e':
                    parseTrueCharacter(buffer, 3);
                    stack.pop();
                    stack.peek().value(Boolean.TRUE);
                    return true;
                default:
                    throw newInvalidJSON(buffer, "invalid 'true' literal");
            }
        }
        return false;
    }

    private void parseTrueCharacter(ByteBuffer buffer, int index)
    {
        Frame frame = stack.peek();
        int value = (Integer)frame.value;
        if (value == index)
            frame.value = ++value;
        else
            throw newInvalidJSON(buffer, "invalid 'true' literal");
    }

    private boolean parseFalse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            byte currentByte = buffer.get();
            switch (currentByte)
            {
                case 'f':
                    if (stack.peek().state != State.FALSE)
                    {
                        stack.push(State.FALSE, 0);
                        parseFalseCharacter(buffer, 0);
                        break;
                    }
                    throw newInvalidJSON(buffer, "invalid 'false' literal");
                case 'a':
                    parseFalseCharacter(buffer, 1);
                    break;
                case 'l':
                    parseFalseCharacter(buffer, 2);
                    break;
                case 's':
                    parseFalseCharacter(buffer, 3);
                    break;
                case 'e':
                    parseFalseCharacter(buffer, 4);
                    stack.pop();
                    stack.peek().value(Boolean.FALSE);
                    return true;
                default:
                    throw newInvalidJSON(buffer, "invalid 'false' literal");
            }
        }
        return false;
    }

    private void parseFalseCharacter(ByteBuffer buffer, int index)
    {
        Frame frame = stack.peek();
        int value = (Integer)frame.value;
        if (value == index)
            frame.value = ++value;
        else
            throw newInvalidJSON(buffer, "invalid 'false' literal");
    }

    private boolean parseNumber(ByteBuffer buffer)
    {
        if (stack.peek().state != State.NUMBER)
            stack.push(State.NUMBER, numberBuilder);

        while (buffer.hasRemaining())
        {
            byte currentByte = buffer.get();
            switch (currentByte)
            {
                case '+':
                case '-':
                    if (numberBuilder.appendSign(currentByte))
                        break;
                    throw newInvalidJSON(buffer, "invalid number");
                case '.':
                case 'E':
                case 'e':
                    if (numberBuilder.appendAlpha(currentByte))
                        break;
                    throw newInvalidJSON(buffer, "invalid number");
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
                    numberBuilder.appendDigit(currentByte);
                    break;
                default:
                    buffer.position(buffer.position() - 1);
                    Number value = numberBuilder.value();
                    stack.pop();
                    stack.peek().value(value);
                    return true;
            }
        }
        return false;
    }

    private boolean parseString(ByteBuffer buffer)
    {
        Frame frame = stack.peek();
        if (buffer.hasRemaining() && frame.state != State.STRING)
        {
            String result = factory.cached(buffer);
            if (result != null)
            {
                frame.value(result);
                return true;
            }
        }

        while (buffer.hasRemaining())
        {
            byte currentByte = buffer.get();
            switch (currentByte)
            {
                // Explicit delimiter, handle push and pop in this method.
                case '"':
                {
                    if (stack.peek().state != State.STRING)
                    {
                        stack.push(State.STRING, stringBuilder);
                        break;
                    }
                    else
                    {
                        String string = stringBuilder.toString();
                        stringBuilder.reset();
                        stack.pop();
                        stack.peek().value(string);
                        return true;
                    }
                }
                case '\\':
                {
                    buffer.position(buffer.position() - 1);
                    if (parseEscape(buffer))
                        break;
                    return false;
                }
                default:
                {
                    stringBuilder.append(currentByte);
                    break;
                }
            }
        }
        return false;
    }

    private boolean parseEscape(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            byte currentByte = buffer.get();
            switch (currentByte)
            {
                case '\\':
                    if (stack.peek().state != State.ESCAPE)
                    {
                        stack.push(State.ESCAPE, stringBuilder);
                        break;
                    }
                    else
                    {
                        return parseEscapeCharacter((char)currentByte);
                    }
                case '"':
                case '/':
                    return parseEscapeCharacter((char)currentByte);
                case 'b':
                    return parseEscapeCharacter('\b');
                case 'f':
                    return parseEscapeCharacter('\f');
                case 'n':
                    return parseEscapeCharacter('\n');
                case 'r':
                    return parseEscapeCharacter('\r');
                case 't':
                    return parseEscapeCharacter('\t');
                case 'u':
                    stack.push(State.UNICODE, ByteBuffer.allocate(4));
                    return parseUnicode(buffer);
                default:
                    throw newInvalidJSON(buffer, "invalid escape sequence");
            }
        }
        return false;
    }

    private boolean parseEscapeCharacter(char escape)
    {
        stack.pop();
        stringBuilder.append(escape);
        return true;
    }

    private boolean parseUnicode(ByteBuffer buffer)
    {
        // Expect 4 hex digits.
        while (buffer.hasRemaining())
        {
            byte currentByte = buffer.get();
            ByteBuffer hex = (ByteBuffer)stack.peek().value;
            hex.put(hexToByte(buffer, currentByte));
            if (!hex.hasRemaining())
            {
                int result = (hex.get(0) << 12) +
                    (hex.get(1) << 8) +
                    (hex.get(2) << 4) +
                    (hex.get(3));
                stack.pop();
                // Also done with escape parsing.
                stack.pop();
                stringBuilder.append((char)result);
                return true;
            }
        }
        return false;
    }

    private byte hexToByte(ByteBuffer buffer, byte currentByte)
    {
        try
        {
            return TypeUtil.convertHexDigit(currentByte);
        }
        catch (Throwable x)
        {
            throw newInvalidJSON(buffer, "invalid hex digit");
        }
    }

    private boolean parseArray(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            byte peek = buffer.get(buffer.position());
            switch (peek)
            {
                // Explicit delimiters, handle push and pop in this method.
                case '[':
                {
                    buffer.get();
                    stack.push(State.ARRAY, newArray(stack));
                    break;
                }
                case ']':
                {
                    buffer.get();
                    Object array = stack.peek().value;
                    stack.pop();
                    stack.peek().value(array);
                    return true;
                }
                case ',':
                {
                    buffer.get();
                    break;
                }
                default:
                {
                    if (isWhitespace(peek))
                    {
                        buffer.get();
                        break;
                    }
                    else
                    {
                        if (parseAny(buffer))
                        {
                            break;
                        }
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private boolean parseObject(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            byte currentByte = buffer.get();
            switch (currentByte)
            {
                // Explicit delimiters, handle push and pop in this method.
                case '{':
                {
                    if (stack.peek().state != State.OBJECT)
                    {
                        stack.push(State.OBJECT, newObject(stack));
                        break;
                    }
                    throw newInvalidJSON(buffer, "invalid object");
                }
                case '}':
                {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> object = (Map<String, Object>)stack.peek().value;
                    stack.pop();
                    stack.peek().value(convertObject(object));
                    return true;
                }
                case ',':
                {
                    break;
                }
                default:
                {
                    if (isWhitespace(currentByte))
                    {
                        break;
                    }
                    else
                    {
                        buffer.position(buffer.position() - 1);
                        if (parseObjectField(buffer))
                            break;
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private boolean parseObjectField(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            byte peek = buffer.get(buffer.position());
            switch (peek)
            {
                case '"':
                {
                    if (stack.peek().state == State.OBJECT)
                    {
                        stack.push(State.OBJECT_FIELD, UNSET);
                        if (parseObjectFieldName(buffer))
                        {
                            // We are not done yet, parse the value.
                            break;
                        }
                        return false;
                    }
                    else
                    {
                        return parseObjectFieldValue(buffer);
                    }
                }
                default:
                {
                    if (isWhitespace(peek))
                    {
                        buffer.get();
                        break;
                    }
                    else if (stack.peek().state == State.OBJECT_FIELD_VALUE)
                    {
                        return parseObjectFieldValue(buffer);
                    }
                    else
                    {
                        throw newInvalidJSON(buffer, "invalid object field");
                    }
                }
            }
        }
        return false;
    }

    private boolean parseObjectFieldName(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            byte peek = buffer.get(buffer.position());
            switch (peek)
            {
                case '"':
                {
                    if (stack.peek().state == State.OBJECT_FIELD)
                    {
                        stack.push(State.OBJECT_FIELD_NAME, UNSET);
                        if (parseString(buffer))
                        {
                            // We are not done yet, parse until the ':'.
                            break;
                        }
                        return false;
                    }
                    else
                    {
                        throw newInvalidJSON(buffer, "invalid object field");
                    }
                }
                case ':':
                {
                    buffer.get();
                    // We are done with the field name.
                    String fieldName = (String)stack.peek().value;
                    stack.pop();
                    // Change state to parse the field value.
                    stack.push(fieldName, State.OBJECT_FIELD_VALUE, UNSET);
                    return true;
                }
                default:
                {
                    if (isWhitespace(peek))
                    {
                        buffer.get();
                        break;
                    }
                    else
                    {
                        throw newInvalidJSON(buffer, "invalid object field");
                    }
                }
            }
        }
        return false;
    }

    private boolean parseObjectFieldValue(ByteBuffer buffer)
    {
        if (stack.peek().value == UNSET)
        {
            if (!parseAny(buffer))
                return false;
        }

        // We are done with the field value.
        Frame frame = stack.peek();
        Object value = frame.value;
        String name = frame.name;
        stack.pop();
        // We are done with the field.
        stack.pop();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>)stack.peek().value;
        map.put(name, value);

        return true;
    }

    private Object convertObject(Map<String, Object> object)
    {
        Object result = convertObject("x-class", object);
        if (result == null)
        {
            result = convertObject("class", object);
            if (result == null)
                return object;
        }
        return result;
    }

    private Object convertObject(String fieldName, Map<String, Object> object)
    {
        String className = (String)object.get(fieldName);
        if (className == null)
            return null;

        Convertible convertible = toConvertible(className);
        if (convertible != null)
        {
            convertible.fromJSON(object);
            return convertible;
        }

        Convertor convertor = factory.getConvertor(className);
        if (convertor != null)
            return convertor.fromJSON(object);

        return null;
    }

    private Convertible toConvertible(String className)
    {
        try
        {
            Class<?> klass = Loader.loadClass(className);
            if (Convertible.class.isAssignableFrom(klass))
                return (Convertible)klass.getConstructor().newInstance();
            return null;
        }
        catch (Throwable x)
        {
            throw new IllegalArgumentException(x);
        }
    }

    protected RuntimeException newInvalidJSON(ByteBuffer buffer, String message)
    {
        Utf8StringBuilder builder = new Utf8StringBuilder();
        builder.append(System.lineSeparator());
        int position = buffer.position();
        if (factory.isDetailedParseException())
        {
            chunks.forEach(chunk -> builder.append(buffer));
        }
        else
        {
            buffer.position(0);
            builder.append(buffer);
            buffer.position(position);
        }
        builder.append(System.lineSeparator());
        String indent = "";
        if (position > 1)
        {
            char[] chars = new char[position - 1];
            Arrays.fill(chars, ' ');
            indent = new String(chars);
        }
        builder.append(indent);
        builder.append("^ ");
        builder.append(message);
        return new IllegalArgumentException(builder.toString());
    }

    private static boolean isWhitespace(byte ws)
    {
        switch (ws)
        {
            case ' ':
            case '\n':
            case '\r':
            case '\t':
                return true;
            default:
                return false;
        }
    }

    /**
     * <p>The state of JSON parsing.</p>
     */
    public interface Context
    {
        /**
         * @return the depth in the JSON structure
         */
        public int depth();
    }

    private enum State
    {
        COMPLETE, NULL, TRUE, FALSE, NUMBER, STRING, ESCAPE, UNICODE, ARRAY, OBJECT, OBJECT_FIELD, OBJECT_FIELD_NAME, OBJECT_FIELD_VALUE
    }

    private static class Frame
    {
        private String name;
        private State state;
        private Object value;

        private void value(Object value)
        {
            switch (state)
            {
                case COMPLETE:
                case STRING:
                case OBJECT_FIELD_NAME:
                case OBJECT_FIELD_VALUE:
                {
                    this.value = value;
                    break;
                }
                case ARRAY:
                {
                    @SuppressWarnings("unchecked")
                    List<Object> array = (List<Object>)this.value;
                    array.add(value);
                    break;
                }
                default:
                {
                    throw new IllegalStateException("invalid state " + state);
                }
            }
        }
    }

    private static class NumberBuilder
    {
        //  1 => positive integer
        //  0 => non-integer
        // -1 => negative integer
        private int integer = 1;
        private long value;
        private StringBuilder builder;

        private boolean appendSign(byte b)
        {
            if (integer == 0)
            {
                if (builder.length() == 0)
                {
                    builder.append((char)b);
                    return true;
                }
                else
                {
                    char c = builder.charAt(builder.length() - 1);
                    if (c == 'E' || c == 'e')
                    {
                        builder.append((char)b);
                        return true;
                    }
                }
                return false;
            }
            else
            {
                if (value == 0)
                {
                    if (b == '-')
                    {
                        if (integer == 1)
                        {
                            integer = -1;
                            return true;
                        }
                    }
                    else
                    {
                        return true;
                    }
                }
            }
            return false;
        }

        private void appendDigit(byte b)
        {
            if (integer == 0)
                builder.append((char)b);
            else
                value = value * 10 + (b - '0');
        }

        private boolean appendAlpha(byte b)
        {
            if (integer == 0)
            {
                char c = builder.charAt(builder.length() - 1);
                if ('0' <= c && c <= '9' && builder.indexOf("" + (char)b) < 0)
                {
                    builder.append((char)b);
                    return true;
                }
            }
            else
            {
                if (builder == null)
                    builder = new StringBuilder(16);
                if (integer == -1)
                    builder.append('-');
                integer = 0;
                builder.append(value);
                builder.append((char)b);
                return true;
            }
            return false;
        }

        private Number value()
        {
            try
            {
                if (integer == 0)
                    return Double.parseDouble(builder.toString());
                return integer * value;
            }
            finally
            {
                reset();
            }
        }

        private void reset()
        {
            integer = 1;
            value = 0;
            if (builder != null)
                builder.setLength(0);
        }
    }

    private static class FrameStack implements AsyncJSON.Context
    {
        private final List<Frame> stack = new ArrayList<>();
        private int cursor;

        private FrameStack()
        {
            grow(6);
        }

        private void grow(int grow)
        {
            for (int i = 0; i < grow; i++)
            {
                stack.add(new Frame());
            }
        }

        private void clear()
        {
            while (!isEmpty())
            {
                pop();
            }
        }

        private boolean isEmpty()
        {
            return cursor == 0;
        }

        @Override
        public int depth()
        {
            return cursor - 1;
        }

        private Frame peek()
        {
            if (isEmpty())
                throw new IllegalStateException("empty stack");
            return stack.get(depth());
        }

        private void push(AsyncJSON.State state, Object value)
        {
            push(null, state, value);
        }

        private void push(String name, AsyncJSON.State state, Object value)
        {
            if (cursor == stack.size())
                grow(2);
            ++cursor;
            Frame frame = stack.get(depth());
            frame.name = name;
            frame.state = state;
            frame.value = value;
        }

        private void pop()
        {
            if (isEmpty())
                throw new IllegalStateException("empty stack");
            Frame frame = stack.get(depth());
            --cursor;
            frame.name = null;
            frame.value = null;
            frame.state = null;
        }
    }
}
