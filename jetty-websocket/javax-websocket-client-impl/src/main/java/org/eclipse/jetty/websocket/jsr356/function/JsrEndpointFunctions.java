//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.function;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.function.CommonEndpointFunctions;
import org.eclipse.jetty.websocket.common.message.PartialBinaryMessageSink;
import org.eclipse.jetty.websocket.common.message.PartialTextMessageSink;
import org.eclipse.jetty.websocket.common.reflect.Arg;
import org.eclipse.jetty.websocket.common.reflect.DynamicArgs;
import org.eclipse.jetty.websocket.common.reflect.UnorderedSignature;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;
import org.eclipse.jetty.websocket.jsr356.JsrSession;
import org.eclipse.jetty.websocket.jsr356.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.jsr356.encoders.AvailableEncoders;
import org.eclipse.jetty.websocket.jsr356.messages.DecodedBinaryMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.DecodedTextMessageSink;

/**
 * Endpoint Functions used as interface between from the parsed websocket frames
 * and the user provided endpoint methods.
 */
public class JsrEndpointFunctions extends CommonEndpointFunctions<JsrSession>
{
    private static final Logger LOG = Log.getLogger(JsrEndpointFunctions.class);
    
    /**
     * Represents a static value (as seen from a URI PathParam)
     * <p>
     * The decoding of the raw String to a object occurs later,
     * when the callable/sink/function is created for a method
     * that needs it converted to an object.
     * </p>
     */
    protected static class StaticArg implements Comparator<StaticArg>
    {
        public final String name;
        public final String value;
        
        public StaticArg(String name, String value)
        {
            this.name = name;
            this.value = value;
        }
        
        @Override
        public int compare(StaticArg o1, StaticArg o2)
        {
            return o1.name.compareTo(o2.name);
        }
    }
    
    private final AvailableEncoders encoders;
    private final AvailableDecoders decoders;
    private final EndpointConfig endpointConfig;
    private List<StaticArg> staticArgs;
    
    public JsrEndpointFunctions(Object endpoint, WebSocketPolicy policy, Executor executor,
                                AvailableEncoders encoders, AvailableDecoders decoders,
                                Map<String, String> uriParams, EndpointConfig endpointConfig)
    {
        super(endpoint, policy, executor);
        this.encoders = encoders;
        this.decoders = decoders;
        this.endpointConfig = endpointConfig;
        
        if (uriParams != null)
        {
            this.staticArgs = new ArrayList<>();
            this.staticArgs.addAll(uriParams.entrySet().stream()
                    .map(entry -> new StaticArg(entry.getKey(), entry.getValue()))
                    .sorted()
                    .collect(Collectors.toList()));
        }
    }
    
    @Override
    protected void discoverEndpointFunctions(Object endpoint)
    {
        if (endpoint instanceof Endpoint)
        {
            Endpoint jsrEndpoint = (Endpoint) endpoint;
            setOnOpen((session) ->
                    {
                        jsrEndpoint.onOpen(session, endpointConfig);
                        return null;
                    },
                    ReflectUtils.findMethod(endpoint.getClass(), "onOpen", Session.class, EndpointConfig.class)
            );
            setOnClose((close) ->
                    {
                        CloseReason closeReason = new CloseReason(
                                CloseReason.CloseCodes.getCloseCode(close.getStatusCode())
                                , close.getReason());
                        jsrEndpoint.onClose(getSession(), closeReason);
                        return null;
                    },
                    ReflectUtils.findMethod(endpoint.getClass(), "onClose", Session.class, EndpointConfig.class)
            );
            setOnError((cause) ->
                    {
                        jsrEndpoint.onError(getSession(), cause);
                        return null;
                    },
                    ReflectUtils.findMethod(endpoint.getClass(), "onError", Session.class, EndpointConfig.class)
            );
            
            // If using an Endpoint, there's nothing else left to map at this point.
            // Eventually, the endpoint should call .addMessageHandler() to declare
            // the various TEXT / BINARY / PONG message functions
            return;
        }
        
        discoverAnnotatedEndpointFunctions(endpoint);
    }
    
    /**
     * Generic discovery of annotated endpoint functions.
     *
     * @param endpoint the endpoint object
     */
    protected void discoverAnnotatedEndpointFunctions(Object endpoint)
    {
        Class<?> endpointClass = endpoint.getClass();
        
        // Use the JSR/Client annotation
        ClientEndpoint websocket = endpointClass.getAnnotation(ClientEndpoint.class);
        
        if (websocket != null)
        {
            encoders.registerAll(websocket.encoders());
            decoders.registerAll(websocket.decoders());
            
            // From here, the discovery of endpoint method is standard across
            // both JSR356/Client and JSR356/Server endpoints
            try
            {
                discoverJsrAnnotatedEndpointFunctions(endpoint);
            }
            catch (DecodeException e)
            {
                throw new InvalidWebSocketException("Cannot instantiate WebSocket", e);
            }
        }
    }
    
    /**
     * JSR356 Specific discovery of Annotated Endpoint Methods
     *
     * @param endpoint the endpoint
     */
    protected void discoverJsrAnnotatedEndpointFunctions(Object endpoint) throws DecodeException
    {
        Class<?> endpointClass = endpoint.getClass();
        Method method = null;
        
        // OnOpen [0..1]
        method = ReflectUtils.findAnnotatedMethod(endpointClass, OnOpen.class);
        if (method != null)
        {
            ReflectUtils.assertIsPublicNonStatic(method);
            ReflectUtils.assertIsReturn(method, Void.TYPE);
            
            // Analyze @OnOpen method declaration techniques
            DynamicArgs.Builder builder = createDynamicArgs(
                    new Arg(Session.class),
                    new Arg(EndpointConfig.class));
            
            DynamicArgs.Signature sig = builder.getMatchingSignature(method);
            assertSignatureValid(sig, OnOpen.class, method);
            
            final Object[] args = newCallArgs(sig.getCallArgs());
            DynamicArgs invoker = builder.build(method, sig);
            setOnOpen((jsrSession) ->
            {
                args[0] = jsrSession;
                args[1] = endpointConfig;
                invoker.invoke(endpoint, args);
                return null;
            }, method);
        }
        
        // OnClose [0..1]
        method = ReflectUtils.findAnnotatedMethod(endpointClass, OnClose.class);
        if (method != null)
        {
            ReflectUtils.assertIsPublicNonStatic(method);
            ReflectUtils.assertIsReturn(method, Void.TYPE);
            
            // Analyze @OnClose method declaration techniques
            DynamicArgs.Builder builder = createDynamicArgs(
                    new Arg(Session.class),
                    new Arg(CloseReason.class));
            
            DynamicArgs.Signature sig = builder.getMatchingSignature(method);
            assertSignatureValid(sig, OnClose.class, method);
            
            final Object[] args = newCallArgs(sig.getCallArgs());
            DynamicArgs invoker = builder.build(method, sig);
            setOnClose((closeInfo) ->
            {
                // Convert Jetty CloseInfo to JSR CloseReason
                CloseReason.CloseCode closeCode = CloseReason.CloseCodes.getCloseCode(closeInfo.getStatusCode());
                CloseReason closeReason = new CloseReason(closeCode, closeInfo.getReason());
                args[0] = getSession();
                args[1] = closeReason;
                invoker.invoke(endpoint, args);
                return null;
            }, method);
        }
        
        // OnError [0..1]
        method = ReflectUtils.findAnnotatedMethod(endpointClass, OnError.class);
        if (method != null)
        {
            ReflectUtils.assertIsPublicNonStatic(method);
            ReflectUtils.assertIsReturn(method, Void.TYPE);
            
            // Analyze @OnError method declaration techniques
            DynamicArgs.Builder builder = createDynamicArgs(
                    new Arg(Session.class),
                    new Arg(Throwable.class));
            
            DynamicArgs.Signature sig = builder.getMatchingSignature(method);
            assertSignatureValid(sig, OnError.class, method);
            
            final Object[] args = newCallArgs(sig.getCallArgs());
            DynamicArgs invoker = builder.build(method, sig);
            setOnError((cause) ->
            {
                args[0] = getSession();
                args[1] = cause;
                invoker.invoke(endpoint, args);
                return null;
            }, method);
        }
        
        // OnMessage [0..3] (TEXT / BINARY / PONG)
        Method onMessages[] = ReflectUtils.findAnnotatedMethods(endpointClass, OnMessage.class);
        if (onMessages != null && onMessages.length > 0)
        {
            for (Method onMsg : onMessages)
            {
                // Analyze @OnMessage method declaration
                
                // Must be a public, non-static method
                ReflectUtils.assertIsPublicNonStatic(onMsg);
                
                // If a return type is declared, it must be capable
                // of being encoded with an available Encoder
                Class<?> returnType = onMsg.getReturnType();
                Encoder returnEncoder = getEncoderFor(returnType);
                
                // Try to determine Message type (BINARY / TEXT) from signature
                
                Arg SESSION = new Arg(Session.class);
                
                // Whole TEXT ---
                for (AvailableDecoders.RegisteredDecoder decoder : decoders.supporting(Decoder.Text.class))
                {
                    UnorderedSignature sig = new UnorderedSignature(createCallArgs(SESSION, new Arg(decoder.objectType).required()));
                    if (sig.test(onMsg))
                    {
                        final Object[] args = newCallArgs(sig.getCallArgs());
                        args[0] = getSession();
                        BiFunction<Object,Object[],Object> invoker = sig.newFunction(onMsg);
                        Decoder.Text decoderInstance = getDecoderInstance(decoder, Decoder.Text.class);
                        DecodedTextMessageSink textSink = new DecodedTextMessageSink(
                                getSession(),
                                decoderInstance,
                                (msg) ->
                                {
                                    args[1] = msg;
                                    return invoker.apply(endpoint, args);
                                }
                        );
                        setOnText(textSink, onMsg);
                        break;
                    }
                }
                
                // Whole BINARY ---
                for(AvailableDecoders.RegisteredDecoder decoder : decoders.supporting(Decoder.Binary.class))
                {
                    UnorderedSignature sig = new UnorderedSignature(createCallArgs(SESSION, new Arg(decoder.objectType).required()));
                    if (sig.test(onMsg))
                    {
                        final Object[] args = newCallArgs(sig.getCallArgs());
                        args[0] = getSession();
                        BiFunction<Object,Object[],Object> invoker = sig.newFunction(onMsg);
                        Decoder.Binary decoderInstance = getDecoderInstance(decoder, Decoder.Binary.class);
                        DecodedBinaryMessageSink binarySink = new DecodedBinaryMessageSink(
                                getSession(),
                                decoderInstance,
                                (msg) ->
                                {
                                    args[1] = msg;
                                    return invoker.apply(endpoint, args);
                                }
                        );
                        setOnBinary(binarySink, onMsg);
                        break;
                    }
                }
                
                // Partial Text ---
                Arg ARG_PARTIAL_BOOL = new Arg(boolean.class).required();
                Arg ARG_STRING = new Arg(String.class).required();
                DynamicArgs.Builder partialTextBuilder = new DynamicArgs.Builder();
                partialTextBuilder.addSignature(createCallArgs(SESSION, ARG_STRING, ARG_PARTIAL_BOOL));
                
                DynamicArgs.Signature sigPartialText = partialTextBuilder.getMatchingSignature(onMsg);
                if (sigPartialText != null)
                {
                    // Found partial text args
                    final Object[] args = newCallArgs(sigPartialText.getCallArgs());
                    
                    args[0] = getSession();
                    DynamicArgs invoker = partialTextBuilder.build(method, sigPartialText);
                    setOnText(new PartialTextMessageSink((partial) ->
                    {
                        args[1] = partial.getPayload();
                        args[2] = partial.isFin();
                        invoker.invoke(endpoint, args);
                        return null;
                    }), onMsg);
                }
                
                // Partial Binary ---
                Arg ARG_BYTE_ARRAY = new Arg(byte[].class).required();
                Arg ARG_BYTE_BUFFER = new Arg(ByteBuffer.class).required();
                DynamicArgs.Builder partialBinaryBuilder = new DynamicArgs.Builder();
                partialBinaryBuilder.addSignature(createCallArgs(SESSION, ARG_BYTE_ARRAY, ARG_PARTIAL_BOOL));
                partialBinaryBuilder.addSignature(createCallArgs(SESSION, ARG_BYTE_BUFFER, ARG_PARTIAL_BOOL));
                
                DynamicArgs.Signature sigPartialBinary = partialBinaryBuilder.getMatchingSignature(onMsg);
                if (sigPartialBinary != null)
                {
                    // Found partial binary args
                    assertPartialMustHaveVoidReturn(onMsg);
                    final Object[] args = newCallArgs(sigPartialBinary.getCallArgs());
                    args[0] = getSession();
                    DynamicArgs invoker = partialBinaryBuilder.build(method, sigPartialBinary);
                    setOnBinary(new PartialBinaryMessageSink((partial) ->
                    {
                        args[1] = partial.getPayload();
                        // TODO: handle byte[] version
                        args[2] = partial.isFin();
                        invoker.invoke(endpoint, args);
                        return null;
                    }), onMsg);
                }
                
                // Streaming TEXT ---
                DynamicArgs.Builder streamingTextBuilder = new DynamicArgs.Builder();
                
                decoders.supporting(Decoder.TextStream.class)
                        .forEach(registeredDecoder ->
                                streamingTextBuilder.addSignature(
                                        createCallArgs(
                                                SESSION, new Arg(registeredDecoder.objectType).required()
                                        )
                                )
                        );
                
                DynamicArgs.Signature sigStreamingText = streamingTextBuilder.getMatchingSignature(onMsg);
                if (sigStreamingText != null)
                {
                    // TODO: Found streaming text args
                    assertPartialMustHaveVoidReturn(onMsg);
                    final Object[] args = newCallArgs(sigStreamingText.getCallArgs());
                }
                
                // Streaming BINARY ---
                DynamicArgs.Builder streamingBinaryBuilder = new DynamicArgs.Builder();
                
                decoders.supporting(Decoder.BinaryStream.class)
                        .forEach(registeredDecoder ->
                                streamingBinaryBuilder.addSignature(
                                        createCallArgs(
                                                SESSION, new Arg(registeredDecoder.objectType).required()
                                        )
                                )
                        );
                
                DynamicArgs.Signature sigStreamingBinary = streamingBinaryBuilder.getMatchingSignature(onMsg);
                if (sigStreamingBinary != null)
                {
                    // TODO: found streaming binary args!
                    final Object[] args = newCallArgs(sigStreamingBinary.getCallArgs());
                    DynamicArgs invoker = streamingBinaryBuilder.build(method, sigStreamingBinary);
                    /*
                    setOnBinary(new DecodedBinaryMessageSink<String>(
                            (msg) ->
                            {
                                args[0] = getSession();
                                args[1] = msg;
                                invoker.invoke(endpoint, args);
                                return null;
                            }), onMsg);
                    */
                }
                
                // Test for PONG
                
                // TODO: super.setOnText()
                // TODO: super.setOnBinary()
                // TODO: super.setOnPong()

                    /*
                    else
                    {
                        // Not a valid @OnMessage declaration signature
                        throw InvalidSignatureException.build(onmsg, OnMessage.class,
                                OnTextFunction.getDynamicArgsBuilder(),
                                OnByteBufferFunction.getDynamicArgsBuilder(),
                                OnByteArrayFunction.getDynamicArgsBuilder(),
                                OnInputStreamFunction.getDynamicArgsBuilder(),
                                OnReaderFunction.getDynamicArgsBuilder());
                    }
                     */
            }
        }
    }
    
    private Encoder getEncoderFor(Class<?> type)
    {
        if ((type == Void.TYPE) || (type == Void.class))
        {
            return null;
        }
        
        // TODO: return a pre-initialized encoder from past call
        
        Class<? extends Encoder> encoderClass = encoders.getEncoderFor(type);
        if (encoderClass == null)
        {
            throw new InvalidWebSocketException("Unable to find Encoder for type " + type.getName());
        }
        
        try
        {
            Encoder encoder = encoderClass.newInstance();
            encoder.init(this.endpointConfig);
            return encoder;
        }
        catch (Throwable t)
        {
            throw new InvalidWebSocketException("Unable to initialize required Encoder: " + encoderClass.getName(), t);
        }
    }
    
    private <T extends Decoder> T getDecoderInstance(AvailableDecoders.RegisteredDecoder registeredDecoder, Class<T> interfaceType)
    {
        // TODO: Return previous instantiated decoders here
        
        Class<? extends Decoder> decoderClass = registeredDecoder.decoder;
        try
        {
            Decoder decoder = decoderClass.newInstance();
            decoder.init(this.endpointConfig);
            return (T) decoder;
        }
        catch (Throwable t)
        {
            throw new InvalidWebSocketException("Unable to initialize required Decoder: " + decoderClass.getName(), t);
        }
    }
    
    private void assertSignatureValid(DynamicArgs.Signature sig, Class<? extends Annotation> annotationClass, Method method)
    {
        if (sig != null)
            return;
        
        StringBuilder err = new StringBuilder();
        err.append('@').append(annotationClass.getSimpleName());
        err.append(' ');
        ReflectUtils.append(err, endpoint.getClass(), method);
        throw new InvalidSignatureException(err.toString());
    }
    
    private void assertPartialMustHaveVoidReturn(Method method)
    {
        if (method.getReturnType().isAssignableFrom(Void.TYPE))
        {
            return;
        }
        
        StringBuilder err = new StringBuilder();
        err.append("Partial @OnMessage handlers must be void return type: ");
        ReflectUtils.append(err, endpoint.getClass(), method);
        throw new InvalidSignatureException(err.toString());
    }
    
    public AvailableDecoders getAvailableDecoders()
    {
        return decoders;
    }
    
    protected Object[] newCallArgs(Arg[] callArgs) throws DecodeException
    {
        int len = callArgs.length;
        Object[] args = new Object[callArgs.length];
        for (int i = 0; i < len; i++)
        {
            Object staticValue = getDecodedStaticValue(callArgs[i].getName(), callArgs[i].getType());
            if (staticValue != null)
            {
                args[i] = staticValue;
            }
        }
        return args;
    }
    
    private Object getDecodedStaticValue(String name, Class<?> type) throws DecodeException
    {
        for (StaticArg args : staticArgs)
        {
            if (args.name.equals(name))
            {
                return AvailableDecoders.decodePrimitive(args.value, type);
            }
        }
        
        return null;
    }
    
    private DynamicArgs.Builder createDynamicArgs(Arg... args)
    {
        DynamicArgs.Builder argBuilder = new DynamicArgs.Builder();
        Arg[] callArgs = createCallArgs(args);
        argBuilder.addSignature(callArgs);
        return argBuilder;
    }
    
    protected Arg[] createCallArgs(Arg... args)
    {
        int argCount = args.length;
        if (this.staticArgs != null)
            argCount += this.staticArgs.size();
        
        Arg callArgs[] = new Arg[argCount];
        int idx = 0;
        for (Arg arg : args)
        {
            callArgs[idx++] = arg;
        }
        
        if (this.staticArgs != null)
        {
            for (StaticArg staticArg : this.staticArgs)
            {
                // TODO: translate from UriParam String to method param type?
                // TODO: shouldn't this be the Arg seen in the method?
                // TODO: use decoder?
                callArgs[idx++] = new Arg(staticArg.value.getClass()).setTag(staticArg.name);
            }
        }
        return callArgs;
    }
}
