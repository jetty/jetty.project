package org.eclipse.jetty.websocket.util;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.util.messages.MessageReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MessageReaderTest
{
    private final MessageReader reader = new MessageReader();
    private final CompletableFuture<String> message = new CompletableFuture<>();
    private boolean first = true;

    @BeforeEach
    public void before()
    {
        // Read the message in a different thread.
        new Thread(() ->
        {
            try
            {
                message.complete(IO.toString(reader));
            }
            catch (IOException e)
            {
                message.completeExceptionally(e);
            }
        }).start();
    }

    @Test
    public void testSingleFrameMessage() throws Exception
    {
        giveString("hello world!", true);

        String s = message.get(5, TimeUnit.SECONDS);
        assertThat(s, is("hello world!"));
    }

    @Test
    public void testFragmentedMessage() throws Exception
    {
        giveString("hello", false);
        giveString(" ", false);
        giveString("world", false);
        giveString("!", true);

        String s = message.get(5, TimeUnit.SECONDS);
        assertThat(s, is("hello world!"));
    }

    @Test
    public void testEmptySegments() throws Exception
    {
        giveString("", false);
        giveString("hello ", false);
        giveString("", false);
        giveString("", false);
        giveString("world!", false);
        giveString("", false);
        giveString("", true);

        String s = message.get(5, TimeUnit.SECONDS);
        assertThat(s, is("hello world!"));
    }

    @Test
    public void testCloseStream() throws Exception
    {
        giveString("hello ", false);
        reader.close();
        giveString("world!", true);

        ExecutionException error = assertThrows(ExecutionException.class, () -> message.get(5, TimeUnit.SECONDS));
        Throwable cause = error.getCause();
        assertThat(cause, instanceOf(IOException.class));
        assertThat(cause.getMessage(), is("Closed"));
    }

    private void giveString(String s, boolean last) throws IOException
    {
        byte opCode = first ? OpCode.TEXT : OpCode.CONTINUATION;
        Frame frame = new Frame(opCode, last, s);
        FutureCallback callback = new FutureCallback();
        reader.accept(frame, callback);
        callback.block(5, TimeUnit.SECONDS);
        first = false;
    }
}
