package org.eclipse.jetty.websocket.servlet;

import java.time.Duration;

import org.eclipse.jetty.http.pathmap.PathSpec;

public interface WebSocketServletFactory
{
    void addFrameHandlerFactory(WebSocketServletFrameHandlerFactory frameHandlerFactory);

    /**
     * add a WebSocket mapping to a provided {@link WebSocketCreator}.
     * <p>
     * If mapping is added before this configuration is started, then it is persisted through
     * stop/start of this configuration's lifecycle.  Otherwise it will be removed when
     * this configuration is stopped.
     * </p>
     *
     * @param pathSpec the pathspec to respond on
     * @param creator the WebSocketCreator to use
     * @since 10.0
     */
    void addMapping(PathSpec pathSpec, WebSocketCreator creator);

    Duration getDefaultIdleTimeout();

    void setDefaultIdleTimeout(Duration duration);

    int getDefaultInputBufferSize();

    void setDefaultInputBufferSize(int bufferSize);

    long getDefaultMaxAllowedFrameSize();

    void setDefaultMaxAllowedFrameSize(long maxFrameSize);

    long getDefaultMaxBinaryMessageSize();

    void setDefaultMaxBinaryMessageSize(long bufferSize);

    long getDefaultMaxTextMessageSize();

    void setDefaultMaxTextMessageSize(long bufferSize);

    int getDefaultOutputBufferSize();

    void setDefaultOutputBufferSize(int bufferSize);

    /**
     * Returns the creator for the given path spec.
     *
     * @param pathSpec the pathspec to respond on
     * @return the websocket creator if path spec exists, or null
     */
    WebSocketCreator getMapping(PathSpec pathSpec);

    /**
     * Get the MappedResource for the given target path.
     *
     * @param target the target path
     * @return the MappedResource if matched, or null if not matched.
     */
    WebSocketCreator getMatch(String target);

    boolean isAutoFragment();

    void setAutoFragment(boolean autoFragment);

    /**
     * Parse a PathSpec string into a PathSpec instance.
     * <p>
     * Recognized Path Spec syntaxes:
     * </p>
     * <dl>
     * <dt><code>/path/to</code> or <code>/</code> or <code>*.ext</code> or <code>servlet|{spec}</code></dt>
     * <dd>Servlet Syntax</dd>
     * <dt><code>^{spec}</code> or <code>regex|{spec}</code></dt>
     * <dd>Regex Syntax</dd>
     * <dt><code>uri-template|{spec}</code></dt>
     * <dd>URI Template (see JSR356 and RFC6570 level 1)</dd>
     * </dl>
     *
     * @param rawSpec the raw path spec as String to parse.
     * @return the {@link PathSpec} implementation for the rawSpec
     */
    PathSpec parsePathSpec(String rawSpec);

    /**
     * Removes the mapping based on the given path spec.
     *
     * @param pathSpec the pathspec to respond on
     * @return true if underlying mapping were altered, false otherwise
     */
    boolean removeMapping(PathSpec pathSpec);
}
