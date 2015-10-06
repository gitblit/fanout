/*
 * Copyright 2015 gitblit.com.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gitblit.fanout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A single-thread NIO implementation of https://github.com/travisghansen/fanout
 * <p>
 * This implementation uses channels and selectors, which are the Java analog of
 * the Linux epoll mechanism used in the original fanout C code.
 *
 * @author James Moger
 */
public class FanoutNioService extends FanoutService {

    private final static Logger logger = LoggerFactory.getLogger(FanoutNioService.class);

    private volatile ServerSocketChannel serviceCh;
    private volatile Selector selector;

    public static void main(String[] args) throws Exception {
        FanoutNioService pubsub = new FanoutNioService(null, DEFAULT_PORT);
        pubsub.setStrictRequestTermination(false);
        pubsub.setAllowAllChannelAnnouncements(false);
        pubsub.start();
    }

    /**
     * Create a single-threaded fanout service.
     *
     * @param port the port for running the fanout PubSub service
     * @throws IOException
     */
    public FanoutNioService(int port) {
        this(null, port);
    }

    /**
     * Create a single-threaded fanout service.
     *
     * @param bindInterface the ip address to bind for the service, may be null
     * @param port          the port for running the fanout PubSub service
     * @throws IOException
     */
    public FanoutNioService(String bindInterface, int port) {
        super(bindInterface, port);
    }

    @Override
    protected boolean isConnected() {
        return serviceCh != null;
    }

    @Override
    protected boolean connect() {
        if (serviceCh == null) {
            try {
                serviceCh = ServerSocketChannel.open();
                serviceCh.configureBlocking(false);
                serviceCh.socket().setReuseAddress(true);
                serviceCh.socket().bind(host == null ? new InetSocketAddress(port) : new InetSocketAddress(host, port));
                selector = Selector.open();
                serviceCh.register(selector, SelectionKey.OP_ACCEPT);
                logger.info("{} is ready", name);
            } catch (IOException e) {
                logger.error("failed to start {}", name, e);
                return false;
            }
        }
        return true;
    }

    @Override
    protected void disconnect() {
        try {
            if (serviceCh != null) {
                // close all active client connections
                Map<String, SocketChannel> clients = getCurrentClientSockets();
                for (Map.Entry<String, SocketChannel> client : clients.entrySet()) {
                    closeClientSocket(client.getKey(), client.getValue());
                }

                // close service socket channel
                logger.debug("closing {} socket channel", name);
                serviceCh.socket().close();
                serviceCh.close();
                serviceCh = null;
                selector.close();
                selector = null;
            }
        } catch (IOException e) {
            logger.error("failed to disconnect {}", name, e);
        }
    }

    @Override
    protected void listen() throws IOException {
        while (selector.select(serviceTimeout) > 0) {
            Set<SelectionKey> keys = selector.selectedKeys();
            Iterator<SelectionKey> keyItr = keys.iterator();
            while (keyItr.hasNext()) {
                SelectionKey key = keyItr.next();
                if (key.isAcceptable()) {
                    // new fanout client connection
                    ServerSocketChannel sch = (ServerSocketChannel) key.channel();
                    try {
                        SocketChannel ch = sch.accept();
                        ch.configureBlocking(false);
                        configureClientSocket(ch.socket());

                        FanoutNioConnection connection = new FanoutNioConnection(ch);
                        addConnection(connection);

                        // register to send the queued message
                        ch.register(selector, SelectionKey.OP_WRITE, connection);
                    } catch (IOException e) {
                        logger.error("error accepting fanout connection", e);
                    }
                } else if (key.isReadable()) {
                    // read fanout client request
                    SocketChannel ch = (SocketChannel) key.channel();
                    FanoutNioConnection connection = (FanoutNioConnection) key.attachment();
                    try {
                        connection.read(ch, isStrictRequestTermination());
                        int replies = 0;
                        Iterator<String> reqItr = connection.requestQueue.iterator();
                        while (reqItr.hasNext()) {
                            String req = reqItr.next();
                            String reply = processRequest(connection, req);
                            reqItr.remove();
                            if (reply != null) {
                                replies++;
                            }
                        }

                        if (replies > 0) {
                            // register to send the replies to requests
                            ch.register(selector, SelectionKey.OP_WRITE, connection);
                        } else {
                            // re-register for next read
                            ch.register(selector, SelectionKey.OP_READ, connection);
                        }
                    } catch (IOException e) {
                        logger.error("fanout connection {} error: {}", connection.id, e.getMessage());
                        removeConnection(connection);
                        closeClientSocket(connection.id, ch);
                    }
                } else if (key.isWritable()) {
                    // asynchronous reply to fanout client request
                    SocketChannel ch = (SocketChannel) key.channel();
                    FanoutNioConnection connection = (FanoutNioConnection) key.attachment();
                    try {
                        connection.write(ch);

                        if (hasConnection(connection)) {
                            // register for next read
                            ch.register(selector, SelectionKey.OP_READ, connection);
                        } else {
                            // Connection was rejected due to load or
                            // some other reason. Close it.
                            closeClientSocket(connection.id, ch);
                        }
                    } catch (IOException e) {
                        logger.error("fanout connection {}: {}", connection.id, e.getMessage());
                        removeConnection(connection);
                        closeClientSocket(connection.id, ch);
                    }
                }
                keyItr.remove();
            }
        }
    }

    protected void closeClientSocket(String id, SocketChannel ch) {
        try {
            ch.close();
        } catch (IOException e) {
            logger.error("fanout connection {}", id, e);
        }
    }

    @Override
    protected void broadcast(Collection<FanoutServiceConnection> connections, String channel, String message) {
        super.broadcast(connections, channel, message);

        // register queued write
        Map<String, SocketChannel> sockets = getCurrentClientSockets();
        for (FanoutServiceConnection connection : connections) {
            SocketChannel ch = sockets.get(connection.id);
            if (ch == null) {
                logger.warn("fanout connection {} has been disconnected", connection.id);
                removeConnection(connection);
                continue;
            }
            try {
                ch.register(selector, SelectionKey.OP_WRITE, connection);
            } catch (IOException e) {
                logger.error("failed to register write op for fanout connection {}", connection.id);
            }
        }
    }

    protected Map<String, SocketChannel> getCurrentClientSockets() {
        Map<String, SocketChannel> sockets = new HashMap<String, SocketChannel>();
        for (SelectionKey key : selector.keys()) {
            if (key.channel() instanceof SocketChannel) {
                SocketChannel ch = (SocketChannel) key.channel();
                String id = FanoutConstants.getRemoteSocketId(ch.socket());
                sockets.put(id, ch);
            }
        }
        return sockets;
    }

    /**
     * FanoutNioConnection handles reading/writing messages from a remote fanout
     * connection.
     *
     * @author James Moger
     */
    static class FanoutNioConnection extends FanoutServiceConnection {
        final ByteBuffer readBuffer;
        final ByteBuffer writeBuffer;
        final List<String> requestQueue;
        final List<String> replyQueue;
        final CharsetDecoder decoder;

        FanoutNioConnection(SocketChannel ch) {
            super(ch.socket());
            readBuffer = ByteBuffer.allocate(FanoutConstants.BUFFER_LENGTH);
            writeBuffer = ByteBuffer.allocate(FanoutConstants.BUFFER_LENGTH);
            requestQueue = new ArrayList<>();
            replyQueue = new ArrayList<>();
            decoder = Charset.forName(FanoutConstants.CHARSET).newDecoder();
        }

        protected void read(SocketChannel ch, boolean strictRequestTermination) throws IOException {
            long bytesRead = 0;
            readBuffer.clear();
            bytesRead = ch.read(readBuffer);
            readBuffer.flip();
            if (bytesRead == -1) {
                throw new IOException("lost client connection, end of stream");
            }
            if (readBuffer.limit() == 0) {
                return;
            }
            CharBuffer cbuf = decoder.decode(readBuffer);
            String req = cbuf.toString();
            String[] lines = req.split(strictRequestTermination ? "\n" : "\n|\r");
            requestQueue.addAll(Arrays.asList(lines));
        }

        protected void write(SocketChannel ch) throws IOException {
            Iterator<String> itr = replyQueue.iterator();
            while (itr.hasNext()) {
                String reply = itr.next();
                writeBuffer.clear();
                logger.debug("fanout reply to {}: {}", id, reply);
                byte[] bytes = reply.getBytes(FanoutConstants.CHARSET);
                writeBuffer.put(bytes);
                if (bytes[bytes.length - 1] != 0xa) {
                    writeBuffer.put((byte) 0xa);
                }
                writeBuffer.flip();

                // loop until write buffer has been completely sent
                int written = 0;
                int toWrite = writeBuffer.remaining();
                while (written != toWrite) {
                    written += ch.write(writeBuffer);
                    try {
                        Thread.sleep(10);
                    } catch (Exception x) {
                    }
                }
                itr.remove();
            }
            writeBuffer.clear();
        }

        @Override
        protected void reply(String content) throws IOException {
            // queue the reply
            // replies are transmitted asynchronously from the requests
            replyQueue.add(content);
        }
    }
}