/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.qpid.tests.protocol.v1_0;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertNull;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.google.common.util.concurrent.JdkFutureAdapters;
import com.google.common.util.concurrent.ListenableFuture;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.hamcrest.CoreMatchers;
import org.hamcrest.core.Is;

import org.apache.qpid.server.bytebuffer.QpidByteBuffer;
import org.apache.qpid.server.protocol.v1_0.framing.TransportFrame;
import org.apache.qpid.server.protocol.v1_0.type.FrameBody;
import org.apache.qpid.server.protocol.v1_0.type.UnsignedInteger;
import org.apache.qpid.server.protocol.v1_0.type.UnsignedShort;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Source;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Target;
import org.apache.qpid.server.protocol.v1_0.type.transport.Attach;
import org.apache.qpid.server.protocol.v1_0.type.transport.Begin;
import org.apache.qpid.server.protocol.v1_0.type.transport.Close;
import org.apache.qpid.server.protocol.v1_0.type.transport.Flow;
import org.apache.qpid.server.protocol.v1_0.type.transport.Open;
import org.apache.qpid.server.protocol.v1_0.type.transport.Role;
import org.apache.qpid.server.protocol.v1_0.type.transport.Transfer;

public class FrameTransport implements AutoCloseable
{
    private static final Set<Integer> AMQP_CONNECTION_IDS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    public static final long RESPONSE_TIMEOUT = 6000;

    private final Channel _channel;
    private final BlockingQueue<Response> _queue = new ArrayBlockingQueue<>(100);
    private final EventLoopGroup _workerGroup;

    private int _amqpConnectionId;
    private short _amqpChannelId;

    public FrameTransport(final InetSocketAddress brokerAddress)
    {
        _workerGroup = new NioEventLoopGroup();

        try
        {
            Bootstrap b = new Bootstrap();
            b.group(_workerGroup);
            b.channel(NioSocketChannel.class);
            b.option(ChannelOption.SO_KEEPALIVE, true);
            b.handler(new ChannelInitializer<SocketChannel>()
            {
                @Override
                public void initChannel(SocketChannel ch) throws Exception
                {
                    ch.pipeline().addLast(new InputHandler(_queue))
                                 .addLast(new OutputHandler());
                }
            });

            _channel = b.connect(brokerAddress).sync().channel();
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws Exception
    {
        try
        {
            _channel.disconnect().sync();
            _channel.close().sync();
        }
        finally
        {
            AMQP_CONNECTION_IDS.remove(_amqpConnectionId);
            _workerGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
        }
    }

    public ListenableFuture<Void> sendProtocolHeader(final byte[] bytes) throws Exception
    {
        ByteBuf buffer = ByteBufAllocator.DEFAULT.buffer();
        buffer.writeBytes(bytes);
        ChannelFuture channelFuture = _channel.writeAndFlush(buffer);
        channelFuture.sync();
        return JdkFutureAdapters.listenInPoolThread(channelFuture);
    }

    public ListenableFuture<Void> sendPerformative(final FrameBody frameBody, UnsignedShort channel) throws Exception
    {
        final List<QpidByteBuffer> payload = frameBody instanceof Transfer ? ((Transfer)frameBody).getPayload() : null;
        TransportFrame transportFrame = new TransportFrame(channel.shortValue(), frameBody, payload);
        ChannelFuture channelFuture = _channel.writeAndFlush(transportFrame);
        channelFuture.sync();
        return JdkFutureAdapters.listenInPoolThread(channelFuture);
    }

    public ListenableFuture<Void> sendPerformative(final FrameBody frameBody) throws Exception
    {
        return sendPerformative(frameBody, UnsignedShort.valueOf(_amqpChannelId));
    }

    public ListenableFuture<Void> sendPipelined(final byte[] protocolHeader, final TransportFrame... frames)
            throws InterruptedException
    {
        ChannelPromise promise = _channel.newPromise();
        if (protocolHeader != null)
        {
            ByteBuf buffer = ByteBufAllocator.DEFAULT.buffer();
            buffer.writeBytes(protocolHeader);
            _channel.write(buffer);
        }
        for (TransportFrame frame : frames)
        {
            _channel.write(frame, promise);
        }
        _channel.flush();
        return JdkFutureAdapters.listenInPoolThread(promise);
    }

    public ListenableFuture<Void> sendPipelined(final TransportFrame... frames) throws InterruptedException
    {
        return sendPipelined(null, frames);
    }

    public Response getNextResponse() throws Exception
    {
        return _queue.poll(RESPONSE_TIMEOUT, TimeUnit.MILLISECONDS);
    }

    public void doProtocolNegotiation() throws Exception
    {
        byte[] bytes = "AMQP\0\1\0\0".getBytes(StandardCharsets.UTF_8);
        sendProtocolHeader(bytes);
        HeaderResponse response = (HeaderResponse) getNextResponse();

        if (!Arrays.equals(bytes, response.getHeader()))
        {
            throw new IllegalStateException("Unexpected protocol header");
        }
    }

    public void doOpenConnection() throws Exception
    {
        doProtocolNegotiation();
        Open open = new Open();

        open.setContainerId(String.format("testContainer-%d", getConnectionId()));
        sendPerformative(open, UnsignedShort.valueOf((short) 0));
        PerformativeResponse response = (PerformativeResponse) getNextResponse();
        if (!(response.getFrameBody() instanceof Open))
        {
            throw new IllegalStateException("Unexpected response to connection Open");
        }
    }

    public void doCloseConnection() throws Exception
    {
        Close close = new Close();

        sendPerformative(close, UnsignedShort.valueOf((short) 0));
        PerformativeResponse response = (PerformativeResponse) getNextResponse();
        if (!(response.getFrameBody() instanceof Close))
        {
            throw new IllegalStateException(String.format(
                    "Unexpected response to connection Close. Expected Close got '%s'", response.getFrameBody()));
        }
    }

    public void doBeginSession() throws Exception
    {
        doOpenConnection();
        Begin begin = new Begin();
        begin.setNextOutgoingId(UnsignedInteger.ZERO);
        begin.setIncomingWindow(UnsignedInteger.ZERO);
        begin.setOutgoingWindow(UnsignedInteger.ZERO);
        _amqpChannelId = (short) 1;
        sendPerformative(begin, UnsignedShort.valueOf(_amqpChannelId));
        PerformativeResponse response = (PerformativeResponse) getNextResponse();
        if (!(response.getFrameBody() instanceof Begin))
        {
            throw new IllegalStateException(String.format(
                    "Unexpected response to connection Begin. Expected Begin got '%s'", response.getFrameBody()));
        }
    }

    public void doAttachReceivingLink(String queueName) throws Exception
    {
        doBeginSession();
        Role localRole = Role.RECEIVER;
        Attach attach = new Attach();
        attach.setName("testReceivingLink");
        attach.setHandle(new UnsignedInteger(0));
        attach.setRole(localRole);
        Source source = new Source();
        source.setAddress(queueName);
        attach.setSource(source);
        Target target = new Target();
        attach.setTarget(target);

        sendPerformative(attach);
        PerformativeResponse response = (PerformativeResponse) getNextResponse();

        assertThat(response, is(notNullValue()));
        assertThat(response.getFrameBody(), is(instanceOf(Attach.class)));
        Attach responseAttach = (Attach) response.getFrameBody();
        assertThat(responseAttach.getSource(), is(notNullValue()));
    }

    public void doAttachSendingLink(final UnsignedInteger handle,
                                    final String destination) throws Exception
    {
        doBeginSession();
        Attach attach = new Attach();
        attach.setName("testSendingLink");
        attach.setHandle(handle);
        attach.setRole(Role.SENDER);
        attach.setInitialDeliveryCount(UnsignedInteger.ZERO);
        Source source = new Source();
        attach.setSource(source);
        Target target = new Target();
        target.setAddress(destination);
        attach.setTarget(target);

        sendPerformative(attach);
        PerformativeResponse response = (PerformativeResponse) getNextResponse();

        assertThat(response, is(notNullValue()));
        assertThat(response.getFrameBody(), is(instanceOf(Attach.class)));
        Attach responseAttach = (Attach) response.getFrameBody();
        assertThat(responseAttach.getTarget(), is(notNullValue()));


        PerformativeResponse flowResponse = (PerformativeResponse) getNextResponse();
        assertThat(flowResponse, Is.is(CoreMatchers.notNullValue()));
        assertThat(flowResponse.getFrameBody(), Is.is(CoreMatchers.instanceOf(Flow.class)));
    }

    public void assertNoMoreResponses() throws Exception
    {
        Response response = getNextResponse();
        assertNull("Unexpected response.", response);
    }

    private int getConnectionId()
    {
        if (_amqpConnectionId == 0)
        {
            _amqpConnectionId = 1;
            while (!AMQP_CONNECTION_IDS.add(_amqpConnectionId))
            {
                ++_amqpConnectionId;
            }
        }
        return _amqpConnectionId;
    }
}
