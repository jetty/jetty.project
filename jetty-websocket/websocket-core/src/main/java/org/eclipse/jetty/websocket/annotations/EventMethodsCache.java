package org.eclipse.jetty.websocket.annotations;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.eclipse.jetty.websocket.frames.BinaryFrame;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.frames.ControlFrame;
import org.eclipse.jetty.websocket.frames.DataFrame;
import org.eclipse.jetty.websocket.frames.PingFrame;
import org.eclipse.jetty.websocket.frames.PongFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;

public class EventMethodsCache
{
    @SuppressWarnings("serial")
    private static class ParamList extends ArrayList<Class<?>[]>
    {
        public void addParams(Class<?>... paramTypes)
        {
            this.add(paramTypes);
        }
    }

    /**
     * Parameter list for &#064;OnWebSocketBinary
     */
    private static final ParamList validBinaryParams;
    /**
     * Parameter list for &#064;OnWebSocketConnect
     */
    private static final ParamList validConnectParams;
    private static final ParamList validCloseParams;
    private static final ParamList validFrameParams;
    private static final ParamList validTextParams;

    static
    {
        validBinaryParams = new ParamList();
        validBinaryParams.addParams(ByteBuffer.class);
        validBinaryParams.addParams(byte[].class,int.class,int.class);
        validBinaryParams.addParams(WebSocketConnection.class,ByteBuffer.class);
        validBinaryParams.addParams(WebSocketConnection.class,byte[].class,int.class,int.class);

        validConnectParams = new ParamList();
        validConnectParams.addParams(WebSocketConnection.class);

        validCloseParams = new ParamList();
        validCloseParams.addParams(int.class,String.class);
        validCloseParams.addParams(WebSocketConnection.class,int.class,String.class);

        validTextParams = new ParamList();
        validTextParams.addParams(String.class);
        validTextParams.addParams(WebSocketConnection.class,String.class);

        validFrameParams = new ParamList();
        validFrameParams.addParams(BaseFrame.class);
        validFrameParams.addParams(BinaryFrame.class);
        validFrameParams.addParams(CloseFrame.class);
        validFrameParams.addParams(ControlFrame.class);
        validFrameParams.addParams(DataFrame.class);
        validFrameParams.addParams(PingFrame.class);
        validFrameParams.addParams(PongFrame.class);
        validFrameParams.addParams(TextFrame.class);

        validFrameParams.addParams(WebSocketConnection.class,BaseFrame.class);
        validFrameParams.addParams(WebSocketConnection.class,BinaryFrame.class);
        validFrameParams.addParams(WebSocketConnection.class,CloseFrame.class);
        validFrameParams.addParams(WebSocketConnection.class,ControlFrame.class);
        validFrameParams.addParams(WebSocketConnection.class,DataFrame.class);
        validFrameParams.addParams(WebSocketConnection.class,PingFrame.class);
        validFrameParams.addParams(WebSocketConnection.class,PongFrame.class);
        validFrameParams.addParams(WebSocketConnection.class,TextFrame.class);
    }

    private ConcurrentHashMap<Class<?>, EventMethods> cache;

    public EventMethodsCache()
    {
        cache = new ConcurrentHashMap<>();
    }

    private void assertUnset(EventMethod event, Class<? extends Annotation> annoClass, Class<?> pojo, Method method)
    {
        if (event == EventMethod.NOOP)
        {
            return;
        }
    }

    private void assertValidParams(Class<?> pojo, Method method, Class<? extends Annotation> annoClass, ParamList validParams)
    {
        boolean valid = false;

        Class<?> actual[] = method.getParameterTypes();
        for (Class<?> params[] : validParams)
        {
            if (isSameParameters(actual,params))
            {
                valid = true;
                break;
            }
        }

        if (!valid)
        {
            // Build big detailed exception to help the developer
            StringBuilder err = new StringBuilder();
            err.append("Invalid declaration of ");
            err.append(method);
            err.append(StringUtil.__LINE_SEPARATOR);

            err.append("Acceptable method declarations for @");
            err.append(annoClass.getSimpleName());
            err.append(" are:");
            for (Class<?> params[] : validParams)
            {
                err.append(StringUtil.__LINE_SEPARATOR);
                err.append("public void ").append(method.getName());
                err.append('(');
                boolean delim = false;
                for (Class<?> type : params)
                {
                    if (delim)
                    {
                        err.append(',');
                    }
                    err.append(' ');
                    err.append(type.getName());
                    if (type.isArray())
                    {
                        err.append("[]");
                    }
                    delim = true;
                }
                err.append(')');
            }

            throw new InvalidWebSocketException(err.toString());
        }
    }

    /**
     * Perform the basic discovery mechanism for WebSocket events from the provided pojo.
     * 
     * @param pojo
     *            the pojo to scan
     * @return the discovered event methods
     * @throws InvalidWebSocketException
     */
    private EventMethods discoverMethods(Class<?> pojo) throws InvalidWebSocketException
    {
        if (WebSocketListener.class.isInstance(pojo))
        {
            return scanListenerMethods(pojo);
        }

        WebSocket anno = pojo.getAnnotation(WebSocket.class);
        if (anno == null)
        {
            throw new InvalidWebSocketException(pojo.getName() + " does not implement " + WebSocketListener.class.getName() + " or use the @"
                    + WebSocket.class.getName() + " annotation");
        }

        return scanAnnotatedMethods(pojo);
    }

    public EventMethods getMethods(Class<?> pojo) throws InvalidWebSocketException
    {
        EventMethods methods = cache.get(pojo);
        if (methods == null)
        {
            methods = discoverMethods(pojo);
            cache.put(pojo,methods);
        }
        return methods;
    }

    private boolean isSameParameters(Class<?>[] actual, Class<?>[] params)
    {
        if(actual.length != params.length) {
            // skip
            return false;
        }

        int len = params.length;
        for(int i=0; i<len; i++) {
            if(!actual[i].equals(params[i])) {
                return false; // not valid
            }
        }

        return true;
    }

    /**
     * Register a pojo with the cache.
     * 
     * @param pojo
     *            the pojo to register with the cache.
     * @throws InvalidWebSocketException
     *             if the pojo does not conform to a WebSocket implementation.
     */
    public void register(Class<?> pojo) throws InvalidWebSocketException
    {
        discoverMethods(pojo);
    }

    private EventMethods scanAnnotatedMethods(Class<?> pojo)
    {
        EventMethods events = new EventMethods(pojo,true);

        for (Method method : pojo.getDeclaredMethods())
        {
            if (method.getAnnotation(OnWebSocketConnect.class) != null)
            {
                assertUnset(events.onConnect,OnWebSocketConnect.class,pojo,method);
                assertValidParams(pojo,method,OnWebSocketConnect.class,validConnectParams);
                events.onConnect = new EventMethod(pojo,method);
                continue;
            }

            if (method.getAnnotation(OnWebSocketBinary.class) != null)
            {
                assertUnset(events.onBinary,OnWebSocketBinary.class,pojo,method);
                assertValidParams(pojo,method,OnWebSocketBinary.class,validBinaryParams);
                events.onBinary = new EventMethod(pojo,method);
                continue;
            }

            if (method.getAnnotation(OnWebSocketClose.class) != null)
            {
                assertUnset(events.onClose,OnWebSocketClose.class,pojo,method);
                assertValidParams(pojo,method,OnWebSocketClose.class,validCloseParams);
                events.onClose = new EventMethod(pojo,method);
                continue;
            }

            if (method.getAnnotation(OnWebSocketText.class) != null)
            {
                assertUnset(events.onText,OnWebSocketText.class,pojo,method);
                assertValidParams(pojo,method,OnWebSocketText.class,validTextParams);
                events.onText = new EventMethod(pojo,method);
                continue;
            }

            if (method.getAnnotation(OnWebSocketFrame.class) != null)
            {
                assertValidParams(pojo,method,OnWebSocketFrame.class,validFrameParams);
                events.addOnFrame(new EventMethod(pojo,method));
                continue;
            }

            // Not a tagged method we are interested in, ignore
        }

        return events;
    }

    private EventMethods scanListenerMethods(Class<?> pojo)
    {
        EventMethods events = new EventMethods(pojo,false);
        // This is a WebSocketListener object
        events.onConnect = new EventMethod(pojo,"onWebSocketConnect",WebSocketConnection.class);
        events.onClose = new EventMethod(pojo,"onWebSocketClose",Integer.TYPE,String.class);
        events.onBinary = new EventMethod(pojo,"onWebSocketBinary",byte[].class,Integer.TYPE,Integer.TYPE);
        events.onText = new EventMethod(pojo,"onWebSocketText",WebSocketConnection.class);
        events.onException = new EventMethod(pojo,"onWebSocketException",WebSocketException.class);

        return events;
    }
}
