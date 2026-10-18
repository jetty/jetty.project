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

package org.eclipse.jetty.util.ajax;

import java.lang.reflect.RecordComponent;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.ajax.JSON.Convertible;
import org.eclipse.jetty.util.ajax.JSON.Convertor;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

import static java.nio.charset.StandardCharsets.UTF_8;

/// A non-blocking JSON parser that can parse partial JSON strings.
///
/// Usage:
///
/// ```
/// AsyncJSON parser = new AsyncJSON.Factory().newAsyncJSON();
/// // Feed the parser with partial JSON string content.
/// parser.parse(chunk1);
/// parser.parse(chunk2);
/// // Tell the parser that the JSON string content
/// // is terminated and get the JSON object back.
/// Map<String, Object> object = parser.complete();
/// ```
///
/// After the call to [#complete()] the parser can be reused to parse
/// another JSON string.
///
/// Custom objects can be created by specifying a `"class"` or
/// `"x-class"` field:
///
/// ```
/// String json = """
/// {
///   "x-class": "com.acme.Person",
///   "firstName": "John",
///   "lastName": "Doe",
///   "age": 42
/// }
/// """
/// parser.parse(json);
/// com.acme.Person person = parser.complete();
/// ```
///
/// Class `com.acme.Person` must either (in order)
/// * Implement [Convertible]
/// * Be mapped with a [Convertor] via [Factory#putConvertor(String, Convertor)]
/// * Be a Java `record` class
///
/// JSON arrays are by default represented with a `List<Object>`, but the
/// Java representation can be customized via [Factory#setArrayConverter(Function)].
public class AsyncJSON
{
    /// The factory that creates AsyncJSON instances.
    ///
    /// The factory can be configured with custom [Convertor]s,
    /// and with cached strings that will not be allocated if they can
    /// be looked up from the cache.
    public static class Factory
    {
        private Index.Mutable<CachedString> cache;
        private Map<String, Convertor> convertors;
        private Function<List<?>, Object> arrayConverter = list -> list;
        private boolean detailedParseException;
        private int nestingMaxDepth = 256;

        /// @return the function to customize the Java representation of JSON arrays
        /// @see #setArrayConverter(Function)
        public Function<List<?>, Object> getArrayConverter()
        {
            return arrayConverter;
        }

        /// Sets the function to convert JSON arrays from their default Java
        /// representation, a `List<Object>`, to another Java data structure
        /// such as an `Object[]`.
        ///
        /// @param arrayConverter the function to customize the Java representation of JSON arrays
        /// @see #getArrayConverter()
        public void setArrayConverter(Function<List<?>, Object> arrayConverter)
        {
            this.arrayConverter = Objects.requireNonNull(arrayConverter);
        }

        /// @return whether a parse failure should report the whole JSON string or just the last chunk
        public boolean isDetailedParseException()
        {
            return detailedParseException;
        }

        /// @param detailedParseException whether a parse failure should report the whole JSON string or just the last chunk
        public void setDetailedParseException(boolean detailedParseException)
        {
            this.detailedParseException = detailedParseException;
        }

        /**
         * @return the nesting maximum depth of JSON objects and arrays (default 256)
         * @see #setNestingMaxDepth(int)
         */
        public int getNestingMaxDepth()
        {
            return nestingMaxDepth;
        }

        /**
         * <p>Sets the maximum nesting depth of JSON objects and arrays.</p>
         *
         * @param nestingMaxDepth the nesting maximum depth of JSON objects and arrays
         */
        public void setNestingMaxDepth(int nestingMaxDepth)
        {
            if (nestingMaxDepth <= 0)
                throw new IllegalArgumentException("Invalid nestingMaxDepth");
            this.nestingMaxDepth = nestingMaxDepth;
        }

        /// @param value the string to cache
        /// @return whether the value can be cached
        public boolean cache(String value)
        {
            if (cache == null)
                cache = new Index.Builder<CachedString>()
                    .caseSensitive(true)
                    .mutable()
                    .build();

            CachedString cached = new CachedString(value);
            if (cached.isCacheable())
            {
                cache.put(cached.encoded, cached);
                return true;
            }
            return false;
        }

        /// Attempts to return a cached string from the buffer bytes.
        ///
        /// In case of a cache hit, the string is returned and the buffer
        /// position updated.
        ///
        /// In case of cache miss, `null` is returned and the buffer
        /// position is left unaltered.
        ///
        /// @param buffer the buffer to look up the string from
        /// @return a cached string or `null`
        protected String cached(RetainableByteBuffer buffer)
        {
            if (cache != null)
            {
                CachedString result = cache.getBest(buffer, 0, buffer.remaining());
                if (result != null)
                {
                    buffer.consume(result.encoded.length());
                    return result.value;
                }
            }
            return null;
        }

        /// @return a new parser instance
        public AsyncJSON newAsyncJSON()
        {
            return new AsyncJSON(this);
        }

        /// Associates the given [Convertor] to the given class name.
        ///
        /// @param className the domain class name such as `com.acme.Person`
        /// @param convertor the [Convertor] that converts `Map` to domain objects
        public void putConvertor(String className, Convertor convertor)
        {
            if (convertors == null)
                convertors = new ConcurrentHashMap<>();
            convertors.put(className, convertor);
        }

        /// Removes the [Convertor] associated with the given class name.
        ///
        /// @param className the class name associated with the [Convertor]
        /// @return the [Convertor] associated with the class name, or `null`
        public Convertor removeConvertor(String className)
        {
            if (convertors != null)
                return convertors.remove(className);
            return null;
        }

        /// Returns the [Convertor] associated with the given class name, if any.
        ///
        /// @param className the class name associated with the [Convertor]
        /// @return the [Convertor] associated with the class name, or `null`
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
                this.encoded = new JSON().toJSON(value);
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
    private List<RetainableByteBuffer> chunks;
    private int nestingDepth;

    public AsyncJSON(Factory factory)
    {
        this.factory = factory;
    }

    // Used by tests only.
    boolean isEmpty()
    {
        return stack.isEmpty();
    }

    /// Feeds the parser with the given bytes chunk.
    ///
    /// @param bytes the bytes to parse
    /// @return whether the JSON parsing was complete
    /// @throws IllegalArgumentException if the JSON is malformed
    public boolean parse(byte[] bytes)
    {
        return parse(bytes, 0, bytes.length);
    }

    /// Feeds the parser with the given bytes chunk.
    ///
    /// @param bytes the bytes to parse
    /// @param offset the offset to start parsing from
    /// @param length the number of bytes to parse
    /// @return whether the JSON parsing was complete
    /// @throws IllegalArgumentException if the JSON is malformed
    public boolean parse(byte[] bytes, int offset, int length)
    {
        return parse(ByteBuffer.wrap(bytes, offset, length));
    }

    /// Feeds the parser with the given buffer chunk.
    ///
    /// @param buffer the buffer to parse
    /// @return whether the JSON parsing was complete
    /// @throws IllegalArgumentException if the JSON is malformed
    public boolean parse(ByteBuffer buffer)
    {
        return parse(RetainableByteBuffer.wrap(buffer));
    }

    /// Feeds the parser with the given buffer chunk.
    ///
    /// @param buffer the buffer to parse
    /// @return whether the JSON parsing was complete
    /// @throws IllegalArgumentException if the JSON is malformed
    public boolean parse(RetainableByteBuffer buffer)
    {
        try
        {
            if (factory.isDetailedParseException())
            {
                if (chunks == null)
                    chunks = new ArrayList<>();
                RetainableByteBuffer.Mutable copy = RetainableByteBuffer.Mutable.allocate(Math.toIntExact(buffer.remaining()), false);
                copy.put(buffer.slice());
                chunks.add(copy);
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
                                byte peek = buffer.get();
                                if (!isWhitespace(peek))
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

    /// Signals to the parser that the parse data is complete, and returns
    /// the object parsed from the JSON chunks passed to the `parse()`
    /// methods.
    ///
    /// @param <R> the type the result is cast to
    /// @return the result of the JSON parsing
    /// @throws IllegalArgumentException if the JSON is malformed
    /// @throws IllegalStateException if the no JSON was passed to the `parse()` methods
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
                        throw newInvalidJSON(RetainableByteBuffer.empty(), "incomplete JSON");
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

    /// When a JSON `{` is encountered during parsing,
    /// this method is called to create a new `Map` instance.
    ///
    /// Subclasses may override to return a custom `Map` instance.
    ///
    /// @param context the parsing context
    /// @return a `Map` instance
    protected Map<String, Object> newObject(Context context)
    {
        return new HashMap<>();
    }

    /// When a JSON `[` is encountered during parsing,
    /// this method is called to create a new `List` instance.
    ///
    /// Subclasses may override to return a custom `List` instance.
    ///
    /// @param context the parsing context
    /// @return a `List` instance
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
        nestingDepth = 0;
    }

    private boolean parseAny(RetainableByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            byte peek = buffer.get(buffer.readPosition());
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
                    buffer.get();
                    if (isWhitespace(peek))
                        break;
                    throw newInvalidJSON(buffer, "unrecognized JSON value");
            }
        }
        return false;
    }

    private boolean parseNull(RetainableByteBuffer buffer)
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

    private void parseNullCharacter(RetainableByteBuffer buffer, int index)
    {
        Frame frame = stack.peek();
        int value = (Integer)frame.value;
        if (value == index)
            frame.value = ++value;
        else
            throw newInvalidJSON(buffer, "invalid 'null' literal");
    }

    private boolean parseTrue(RetainableByteBuffer buffer)
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

    private void parseTrueCharacter(RetainableByteBuffer buffer, int index)
    {
        Frame frame = stack.peek();
        int value = (Integer)frame.value;
        if (value == index)
            frame.value = ++value;
        else
            throw newInvalidJSON(buffer, "invalid 'true' literal");
    }

    private boolean parseFalse(RetainableByteBuffer buffer)
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

    private void parseFalseCharacter(RetainableByteBuffer buffer, int index)
    {
        Frame frame = stack.peek();
        int value = (Integer)frame.value;
        if (value == index)
            frame.value = ++value;
        else
            throw newInvalidJSON(buffer, "invalid 'false' literal");
    }

    private boolean parseNumber(RetainableByteBuffer buffer)
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
                    buffer.readPosition(buffer.readPosition() - 1);
                    Number value = numberBuilder.value();
                    stack.pop();
                    stack.peek().value(value);
                    return true;
            }
        }
        return false;
    }

    private boolean parseString(RetainableByteBuffer buffer)
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
                        String string = stringBuilder.takeCompleteString(null);
                        stack.pop();
                        stack.peek().value(string);
                        return true;
                    }
                }
                case '\\':
                {
                    buffer.readPosition(buffer.readPosition() - 1);
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

    private boolean parseEscape(RetainableByteBuffer buffer)
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

    private boolean parseUnicode(RetainableByteBuffer buffer)
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

    private byte hexToByte(RetainableByteBuffer buffer, byte currentByte)
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

    private boolean parseArray(RetainableByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            byte peek = buffer.get(buffer.readPosition());
            switch (peek)
            {
                // Explicit delimiters, handle push and pop in this method.
                case '[':
                {
                    buffer.get();
                    enterNesting(buffer);
                    stack.push(State.ARRAY, newArray(stack));
                    break;
                }
                case ']':
                {
                    buffer.get();
                    @SuppressWarnings("unchecked")
                    List<Object> array = (List<Object>)stack.peek().value;
                    stack.pop();
                    exitNesting();
                    stack.peek().value(convertArray(array));
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

    private boolean parseObject(RetainableByteBuffer buffer)
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
                        enterNesting(buffer);
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
                    exitNesting();
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
                        buffer.readPosition(buffer.readPosition() - 1);
                        if (parseObjectField(buffer))
                            break;
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private void enterNesting(RetainableByteBuffer buffer)
    {
        int nestingMaxDepth = factory.getNestingMaxDepth();
        if (nestingDepth >= nestingMaxDepth)
            throw newInvalidJSON(buffer, "max nesting depth " + nestingMaxDepth + " exceeded");
        ++nestingDepth;
    }

    private void exitNesting()
    {
        --nestingDepth;
    }

    private boolean parseObjectField(RetainableByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            byte peek = buffer.get(buffer.readPosition());
            if (peek == '"')
            {
                if (stack.peek().state == State.OBJECT)
                {
                    stack.push(State.OBJECT_FIELD, UNSET);
                    if (parseObjectFieldName(buffer))
                    {
                        // We are not done yet, parse the value.
                        continue;
                    }
                    return false;
                }
                else
                {
                    return parseObjectFieldValue(buffer);
                }
            }
            else
            {
                if (isWhitespace(peek))
                {
                    buffer.get();
                }
                else if (stack.peek().state == State.OBJECT_FIELD_VALUE)
                {
                    return parseObjectFieldValue(buffer);
                }
                else
                {
                    buffer.get();
                    throw newInvalidJSON(buffer, "invalid object field");
                }
            }
        }
        return false;
    }

    private boolean parseObjectFieldName(RetainableByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            byte peek = buffer.get(buffer.readPosition());
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
                        buffer.get();
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
                        buffer.get();
                        throw newInvalidJSON(buffer, "invalid object field");
                    }
                }
            }
        }
        return false;
    }

    private boolean parseObjectFieldValue(RetainableByteBuffer buffer)
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

    private Object convertArray(List<?> array)
    {
        return factory.getArrayConverter().apply(array);
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

        Object record = toRecord(className, object);
        if (record != null)
            return record;

        return object;
    }

    private static Number convertNumber(Class<?> type, Number number)
    {
        if (type == byte.class || type == Byte.class)
            return number.byteValue();
        if (type == short.class || type == Short.class)
            return number.shortValue();
        if (type == int.class || type == Integer.class)
            return number.intValue();
        if (type == long.class || type == Long.class)
            return number.longValue();
        if (type == float.class || type == Float.class)
            return number.floatValue();
        if (type == double.class || type == Double.class)
            return number.doubleValue();
        throw new NumberFormatException("unsupported number type: " + type);
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
            return null;
        }
    }

    protected Object toRecord(String className, Map<String, Object> object)
    {
        try
        {
            Class<?> klass = Loader.loadClass(className);
            if (!klass.isRecord())
                return null;
            return toRecord(klass, object);
        }
        catch (Throwable x)
        {
            return null;
        }
    }

    protected static Object toRecord(Class<?> klass, Map<String, Object> object)
    {
        try
        {
            RecordComponent[] components = klass.getRecordComponents();
            Class<?>[] types = new Class<?>[components.length];
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; ++i)
            {
                RecordComponent component = components[i];
                if (!object.containsKey(component.getName()))
                    return null;
                types[i] = component.getType();
                Object value = object.get(component.getName());
                if (value instanceof Number number)
                    value = convertNumber(types[i], number);
                values[i] = value;
            }
            return klass.getConstructor(types).newInstance(values);
        }
        catch (Throwable x)
        {
            return null;
        }
    }

    protected RuntimeException newInvalidJSON(RetainableByteBuffer buffer, String message)
    {
        RetainableByteBuffer json;
        boolean elide;
        long errorPosition;
        if (factory.isDetailedParseException())
        {
            json = RetainableByteBuffer.merge(chunks);
            chunks.forEach(RetainableByteBuffer::release);
            chunks.clear();
            elide = false;
            errorPosition = json.remaining() - buffer.remaining() - 1;
        }
        else
        {
            json = buffer;
            elide = true;
            errorPosition = buffer.readPosition() - 1;
        }
        errorPosition = Math.max(0, errorPosition);

        long end = json.readPosition() + json.remaining();
        long before = Math.min(errorPosition, end);
        long after = Math.min(errorPosition + 1, end);
        StringBuilder builder = new StringBuilder(message);
        builder.append(System.lineSeparator());

        // Build a string such of this form:
        // [[begin-bytes]...][before-error-bytes]<x>[after-error-bytes][[...[end-bytes]]
        // where [] means optional, and <x> surrounds the error byte x,
        // and the before/after error bytes are up to 32 bytes.
        appendJSON(builder, json, 0, before, elide, true);
        builder.append("<");
        appendJSON(builder, json, before, after, false, false);
        builder.append(">");
        appendJSON(builder, json, after, end, elide, false);

        return new IllegalArgumentException(builder.toString());
    }

    private static void appendJSON(StringBuilder builder, RetainableByteBuffer json, long from, long to, boolean elide, boolean beforeError)
    {
        long length = to - from;
        // The bytes that exceed the 32 bytes near the error.
        long rest = length - 32;
        // The ellipsis must replace at least 3 bytes.
        if (!elide || rest <= 3)
        {
            builder.append(json.getString(from, length, UTF_8));
            return;
        }
        // The bytes far from the error, up to 32.
        long far = Math.min(32, rest - 3);
        if (beforeError)
        {
            builder.append(json.getString(from, far, UTF_8));
            builder.append("...");
            builder.append(json.getString(to - 32, 32, UTF_8));
        }
        else
        {
            builder.append(json.getString(from, 32, UTF_8));
            builder.append("...");
            builder.append(json.getString(to - far, far, UTF_8));
        }
    }

    private static boolean isWhitespace(byte ws)
    {
        return switch (ws)
        {
            case ' ', '\n', '\r', '\t' -> true;
            default -> false;
        };
    }

    /// The state of JSON parsing.
    public interface Context
    {
        /// @return the depth in the JSON structure
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
                if (builder.isEmpty())
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
