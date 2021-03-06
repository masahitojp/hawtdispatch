/*
 * Copyright 2012 The Netty Project
 * Copyright 2013 Red Hat, Inc.
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.fusesource.hawtdispatch.netty;

import io.netty.buffer.BufType;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DefaultServerSocketChannelConfig;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.ServerSocketChannelConfig;
import io.netty.logging.InternalLogger;
import io.netty.logging.InternalLoggerFactory;
import org.fusesource.hawtdispatch.*;
import static org.fusesource.hawtdispatch.Dispatch.*;
import static java.nio.channels.SelectionKey.*;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousCloseException;

/**
 * {@link io.netty.channel.socket.ServerSocketChannel} implementation which uses HawtDispatch.
 *
 * NIO2 is only supported on Java 7+.
 */
public class HawtServerSocketChannel extends HawtAbstractChannel implements ServerSocketChannel {

    private static final ChannelMetadata METADATA = new ChannelMetadata(BufType.MESSAGE, false);
    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(HawtServerSocketChannel.class);

    private boolean closed;

    private static java.nio.channels.ServerSocketChannel newSocket() {
        try {
            return java.nio.channels.ServerSocketChannel.open();
        } catch (IOException e) {
            throw new ChannelException(
                    "Failed to open a server socket.", e);
        }
    }

    private final ServerSocketChannelConfig config;
    private final DispatchQueue queue;

    /**
     * Create a new instance
     */
    public HawtServerSocketChannel() {
        super(null, null, newSocket());
        queue = createQueue();
        config = new DefaultServerSocketChannelConfig(this, javaChannel().socket());
    }

    @Override
    protected java.nio.channels.ServerSocketChannel javaChannel() {
        return (java.nio.channels.ServerSocketChannel) super.javaChannel();
    }

    @Override
    public boolean isActive() {
        return ch != null && javaChannel().isOpen() && localAddress0() != null;
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    protected SocketAddress localAddress0() {
        return javaChannel().socket().getLocalSocketAddress();
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return null;
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        javaChannel().socket().bind(localAddress, config.getBacklog());
    }

    DispatchSource acceptSource;

    @Override
    protected void doBeginRead() {
        if( acceptSource==null ) {
            acceptSource = createSource(javaChannel(), OP_READ, queue);
            acceptSource.setEventHandler(new Task(){
              public void run() {
                  HawtSocketChannel socket = null;
                  try {
                      socket = new HawtSocketChannel(HawtServerSocketChannel.this, null, javaChannel().accept());
                  } catch (IOException e) {
                      if (isOpen()) {
                          logger.warn("Failed to create a new channel from an accepted socket.", e);
                      }
                  }
                  pipeline().inboundMessageBuffer().add(socket);
                  pipeline().fireInboundBufferUpdated();
                  pipeline().fireInboundBufferSuspended();
              }
            });
            acceptSource.setCancelHandler(new Task(){
                @Override
                public void run() {
                    pipeline().fireChannelUnregistered();
                    try {
                        javaChannel().close();
                    } catch (IOException e) {
                    }
                }
            });
            acceptSource.resume();
            pipeline().fireChannelRegistered();
        }
    }

    @Override
    protected void doClose() throws Exception {
        if (!closed) {
            closed = true;
            acceptSource.cancel();
        }
    }

    @Override
    protected boolean isFlushPending() {
        return false;
    }

    @Override
    protected void doConnect(
            SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        promise.setFailure(new UnsupportedOperationException());
    }

    @Override
    protected void doDisconnect() throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServerSocketChannelConfig config() {
        return config;
    }
}
