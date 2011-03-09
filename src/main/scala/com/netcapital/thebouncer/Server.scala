// Copyright (C) 2011 by NetCapital LLC
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.

package com.netcapital.thebouncer

import scala.collection.mutable

import java.net.InetSocketAddress
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicLong
import java.nio.charset.Charset
import java.util.{UUID => JavaUUID}

import org.jboss.netty.buffer.ChannelBuffer
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel.ChannelPipeline
import org.jboss.netty.channel.ChannelPipelineFactory
import org.jboss.netty.channel.Channels
import org.jboss.netty.channel.ChannelEvent
import org.jboss.netty.channel.ChannelHandlerContext
import org.jboss.netty.channel.ChannelStateEvent
import org.jboss.netty.channel.ExceptionEvent
import org.jboss.netty.channel.MessageEvent
import org.jboss.netty.channel.SimpleChannelUpstreamHandler
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.channel.group._
import org.jboss.netty.handler.codec.frame._
import org.jboss.netty.handler.codec.protobuf._
import org.jboss.netty.handler.execution.ExecutionHandler
import org.jboss.netty.handler.execution.OrderedMemoryAwareThreadPoolExecutor
import org.jboss.netty.handler.timeout._
import org.jboss.netty.util.HashedWheelTimer
import org.jboss.netty.handler.logging.LoggingHandler
import org.jboss.netty.logging.InternalLogLevel

import com.netcapital.util._
import com.netcapital.thebouncer.protocol._
import com.netcapital.thebouncer.protocol.Handshake._
import net.lag.configgy.Configgy

import org.scala_tools.time.Imports._
import com.twitter.json._


object Server extends Logging {
  // Configure the server
  val bossExecutor:ExecutorService = Executors.newCachedThreadPool()
  val workerExecutor:ExecutorService = Executors.newCachedThreadPool()
  val channelFactory = new NioServerSocketChannelFactory(bossExecutor, workerExecutor)
  val bootstrap = new ServerBootstrap(channelFactory)
  val timer = new HashedWheelTimer()
  val orderedMemoryAwareThreadPoolExecutor = new OrderedMemoryAwareThreadPoolExecutor(
    100, // core pool size
    0,   // maxChannelMemorySize, 0 to disable,
    0    // maxTotalMemorySize, 0 to disable
  )
  val executionHandler = new ExecutionHandler(orderedMemoryAwareThreadPoolExecutor)


  def main(args:Array[String]) : Unit = {
    log.info("Launching Server on port 5656")
    Server.startup(5656)
    log.info("Finished init; Server is launched")
  }

  def startup(port:Int) = {
    log.info("Configuring Netty server")

    // Set up the pipeline factory
    bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
      def getPipeline():ChannelPipeline = {
        val pipeline = Channels.pipeline()
        // Setup initially for handshaking/authentication. This pipeline will get changed
        // once the handshake is complete.
        pipeline.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(1024 * 64, 0, 4, 0, 4))
        // This will change after authentication
        pipeline.addLast("protobufDecoder", new ProtobufDecoder(ClientHandshake.getDefaultInstance()))
        pipeline.addLast("frameEncoder", new LengthFieldPrepender(4, false))
        pipeline.addLast("protobufEncoder", new ProtobufEncoder())
        pipeline.addLast("pipelineExecuter", executionHandler)
        pipeline.addLast("handshakeIdleHandler", new IdleStateHandler(timer, 0, 0, 60))
        pipeline.addLast("handshakeHandler", new MyServerHandshakeHandler())
        pipeline.addLast("idleHandler", new IdleStateHandler(timer, 60, 0, 0))
        pipeline.addLast("handler", new MyServerHandler())
        pipeline
      }
    })

    // Bind and start to accept incoming connections.
    bootstrap.setOption("child.keepAlive", true) // for mobiles & our stateful app
    bootstrap.setOption("child.tcpNoDelay", true) // better latency over bandwidth
    bootstrap.setOption("reuseAddress", true) // kernel optimization
    bootstrap.setOption("child.reuseAddress", true) // kernel optimization

    bootstrap.bind(new InetSocketAddress(port))
  }

  def shutdown = {
    MyServerHandler.authenticatedChannels.close().awaitUninterruptibly()
    timer.stop()
    bootstrap.releaseExternalResources
    orderedMemoryAwareThreadPoolExecutor.shutdownNow
    channelFactory.releaseExternalResources
    bossExecutor.shutdownNow
    workerExecutor.shutdownNow
  }
}
