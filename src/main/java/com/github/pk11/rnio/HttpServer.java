package com.github.pk11.rnio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.SelectableChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.StringTokenizer;
import java.util.Arrays;
import static reactor.Fn.$;
import reactor.fn.*;
import reactor.core.*;
import reactor.R;
import java.net.StandardSocketOptions;

public class HttpServer implements Runnable {

    protected final Selector selector = openSelector();
    protected final ServerSocketChannel server = openChannel();


    public final Reactor reactor = R.reactor()
                                   .using(new Environment())
                                   .dispatcher(Environment.RING_BUFFER)
                                   .get();


    public HttpServer handler (final Handler rhandler) {
        reactor.on($("channelhandler"), new Consumer<Event<SocketChannel>>() {
            public void accept(Event<SocketChannel> ev) {
                try {
                    SocketChannel channel = ev.getData();
                    if (channel.isOpen()) {
                        HTTPRequest request = new HTTPRequest(read(toBuffer(channel)));
                        rhandler.handle(request, new HTTPResponse(channel));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    sneakyThrow(e);
                }

            }
        });

        return this;
    }

    public HttpServer(int port) {
        try {
            server.socket().bind(new InetSocketAddress(port));
            server.configureBlocking(false);
            server.register(selector, SelectionKey.OP_ACCEPT);
        } catch (IOException ex) {
            sneakyThrow(ex);
        }
    }

    public void run() {
        while (true) {
            try {
                int cnt = selector.select();
                if (cnt > 0) {
                    Iterator<SelectionKey> i = selector.selectedKeys().iterator();
                    while (i.hasNext()) {
                        SelectionKey key = i.next();
                        i.remove();
                        if (key.isValid()) {
                            try {
                                if (key.isAcceptable()) {
                                    SocketChannel channel = server.accept();
                                    channel.configureBlocking(false);
                                    channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
                                    channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                                    channel.register(selector, SelectionKey.OP_READ);
                                } else if (key.isReadable()) {
                                    SocketChannel channel = (SocketChannel) key.channel();
                                    reactor.notify("channelhandler", Event.wrap(channel));
                                }
                            } catch (Exception ex) {
                                SelectableChannel s  = key.channel();
                                System.err.println("client: " + s);
                                ex.printStackTrace();
                                try {
                                    s.close();
                                } catch (IOException ie) {
                                    ie.printStackTrace();
                                }
                            }
                        }
                    }
                }
            } catch (IOException ex) {
                try {
                    selector.close();
                    server.close();
                } catch (IOException innerEx) {
                    innerEx.printStackTrace();
                }
                throw new RuntimeException(ex);
            }
        }

    }

    protected String read(ByteBuffer buffer) throws IOException {
        StringBuilder l = new StringBuilder();
        StringBuilder lines = new StringBuilder();
        while (buffer.hasRemaining()) {
            char c = (char) buffer.get();
            l.append(c);
            if (c == '\n') {
                lines.append(l);
                l.setLength(0);
            }
        }
        return lines.toString();
    }

    protected ByteBuffer toBuffer(SocketChannel channel) throws IOException {
        final ByteBuffer buffer = ByteBuffer.allocate(2048);
        buffer.limit(buffer.capacity());
        int read = channel.read(buffer);
        if (read == -1) throw new IOException("stream has ended");
        buffer.flip();
        buffer.position(0);
        return buffer;
    }
    // ~~~ nested classes/interfaces ~~~


    public static class HTTPRequest {

        public final String method;
        public final String location;
        public final String version;
        public final Map<String, String> headers = new HashMap<>();

        public HTTPRequest(String raw) {
            StringTokenizer tokenizer = new StringTokenizer(raw);
            method = tokenizer.nextToken().toUpperCase();
            location = tokenizer.nextToken();
            version = tokenizer.nextToken();
            String[] lines = raw.split("\r\n");
            for (int i = 1; i < lines.length; i++) {
                String[] keyVal = lines[i].split(":", 2);
                headers.put(keyVal[0], keyVal[1]);
            }
        }

    }

    public static class HTTPResponse {

        private final SocketChannel channel;
        private final Charset charset = Charset.forName("UTF-8");
        private final CharsetEncoder encoder = charset.newEncoder();
        byte[] raw;
        int responseCode = 200;
        String responseReason = "OK";
        public final String version = "HTTP/1.1";
        public final Map<String, String> headers = new LinkedHashMap<>();



        public HTTPResponse(SocketChannel channel) {
            this.channel = channel;
        }

        public void end() {
            headers.put("Date", new Date().toString());
            headers.put("Server", "Reactor NIO Server");
            headers.put("Connection", "close");
            headers.put("Content-Length", Integer.toString(raw.length));
            try {
                //write top header
                writeLine(channel, version + " " + responseCode + " " + responseReason);
                //write response headers
                writeHeaders(channel, this);
                //write content
                writeLine(channel, "");
                channel.write(ByteBuffer.wrap(raw));
                //close channel
                channel.close();
            } catch (IOException ex) {
                sneakyThrow(ex);
            }
        }

        public void chunked() {
            headers.put("Date", new Date().toString());
            headers.put("Server", "Reactor NIO Server");
            headers.put("Transfer-Encoding", "chunked");
            try {
                //write top header
                writeLine(channel, version + " " + responseCode + " " + responseReason);
                //write headers
                writeHeaders(channel, this);
            }    catch (IOException ex) {
                sneakyThrow(ex);
            }

        }

        public void flushChunks() {
            try {
                writeLine(channel, "Content-Length" + ": " +  Integer.toString(raw.length));
                channel.close();
            } catch (IOException ex) {
                sneakyThrow(ex);
            }
        }

        public HTTPResponse code(int responseCode) {
            this.responseCode = responseCode;
            return this;
        }

        public HTTPResponse reason(String responseReason) {
            this.responseReason = responseReason;
            return this;
        }

        public HTTPResponse content(String content) {
            this.raw = content.getBytes();
            return this;
        }

        public HTTPResponse append(byte[] content) {
            byte[] combined = new byte[this.raw.length + content.length];
            System.arraycopy(this.raw, 0, combined, 0, this.raw.length);
            System.arraycopy(content, 0, content, this.raw.length, content.length);
            this.raw = combined;
            return this;
        }

        public HTTPResponse writeChunk(byte[] content) {
            try {
                channel.write(ByteBuffer.wrap(content));
            } catch (IOException ex) {
                sneakyThrow(ex);
            }
            return append(content);
        }

        public HTTPResponse header(String key, String value) {
            headers.put(key, value);
            return this;
        }

        private void writeLine(SocketChannel channel, String line) throws IOException {
            channel.write(encoder.encode(CharBuffer.wrap(line + "\r\n")));
        }

        private void writeHeaders(SocketChannel channel, HTTPResponse response) {
            try {
                for (Map.Entry<String, String> header : response.headers.entrySet()) {
                    writeLine(channel, header.getKey() + ": " + header.getValue());
                }
            } catch (IOException ex) {
                sneakyThrow(ex);
            }
        }
    }
    public static interface Handler {
        public void handle(HTTPRequest request, HTTPResponse response);
    }
    // ~~~  utils  ~~~

    //http://www.mail-archive.com/javaposse@googlegroups.com/msg05984.html
    private static RuntimeException sneakyThrow(Throwable t) {
        if ( t == null ) throw new NullPointerException("t");
        HttpServer.<RuntimeException>sneakyThrow0(t);
        return null;
    }
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow0(Throwable t)
    throws T {
        throw (T)t;
    }
    private Selector openSelector() {
        Selector s = null;
        try {
            s = Selector.open();
        } catch (IOException ex) {
            sneakyThrow(ex);
        }
        return s;
    }
    private ServerSocketChannel openChannel() {
        ServerSocketChannel s = null;
        try {
            s = ServerSocketChannel.open();
        } catch (IOException ex) {
            sneakyThrow(ex);
        }
        return s;
    }

    // ~~~  app code ~~~

    public static class DemoHandler implements Handler {
        public void handle(HTTPRequest request, HTTPResponse response) {
            //note:
            //this is single-thread so any serious work would need to be
            //executed on a separate thread
            System.out.println("hello reactor...");
            response.content("Hello Reactor!").end();
        }
    }

    public static void main(String[] args) {
        int port = 9999;
        HttpServer httpServer = new HttpServer(port).handler(new DemoHandler());
        System.out.println("startup up a server at port:" + port + " waiting for requests...");
        httpServer.run();
    }

}
