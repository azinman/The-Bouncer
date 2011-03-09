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
import scala.collection.JavaConversions._

import java.net.InetSocketAddress
import java.util.concurrent._
import java.util.concurrent.atomic._
import java.nio.charset.Charset
import java.util.{UUID => JavaUUID}

import org.jboss.netty.buffer.ChannelBuffer
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel._
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.channel.group._
import org.jboss.netty.handler.codec.protobuf._
import org.jboss.netty.handler.execution._
import org.jboss.netty.handler.timeout._
import org.jboss.netty.util.HashedWheelTimer

import com.netcapital.util._
import net.lag.configgy.Configgy

import org.scala_tools.time.Imports._
import com.twitter.json._
import com.google.protobuf.GeneratedMessage

object ServerHandshakeHandler {
  val unauthenticatedChannels = new DefaultChannelGroup()
  val HANDSHAKE_TIMEOUT = 10 * 1000
}

class ServerHandshakeTimeoutHandler extends IdleStateAwareChannelUpstreamHandler with Logging {
  override def channelIdle(ctx:ChannelHandlerContext, e:IdleStateEvent):Unit = {
    log.info("Client handshake timed out, closing connection")
    ctx.getChannel.close
  }
}

class ServerHandshakeSuccessfulEvent(val channel:Channel) extends ChannelEvent {
  override def getChannel() = channel

  override def getFuture()  = Channels.succeededFuture(channel)
}

abstract class ServerHandshakeHandler extends IdleStateAwareChannelHandler with Logging {
  // internal vars ----------------------------------------------------------
  val handshakeComplete = new AtomicBoolean(false)
  val handshakeFailed = new AtomicBoolean(false)
  val handshakeMutex = new Object()
  val messages = new LinkedBlockingDeque[MessageEvent]()
  val latch = new CountDownLatch(1)

  override def channelIdle(ctx:ChannelHandlerContext, e:IdleStateEvent) = handshakeFailed(ctx)

  /**
   * Given the client message, return (successEvent, serverResponse)
   *
   * If successEvent is defined, then we have performed the handshake accordingly well.
   * Therefore, bubble that event up. Otherwise, we will send the response to the
   * client and disconnect.
   */
  def determineResponse(ctx:ChannelHandlerContext, msg:Object):Tuple2[Option[ServerHandshakeSuccessfulEvent], GeneratedMessage]

  // Modified from http://bruno.biasedbit.com/blag/2010/07/15/handshaking-tutorial-with-netty
  override def messageReceived(ctx:ChannelHandlerContext, e:MessageEvent):Unit = {
    if (handshakeFailed.get) {
      // Bail out fast if handshake already failed
      return
    }

    if (handshakeComplete.get) {
      // If handshake succeeded but message still came through this
      // handler, then immediately send it upwards.
      super.messageReceived(ctx, e)
      return
    }

    val channelClosingFuture = Channels.future(ctx.getChannel)
    channelClosingFuture.addListener(new ChannelFutureListener() {
      def operationComplete(future:ChannelFuture) = {
        // log.info("Finished writing server response--closing channel")
        closeChannel(ctx)
      }
    })

    handshakeMutex.synchronized {
      // Recheck conditions after locking the mutex.
      // Things might have changed while waiting for the lock.
      if (handshakeFailed.get) {
        return
      }

      if (handshakeComplete.get) {
        super.messageReceived(ctx, e)
        return
      }

      // Validate handshake
      val clientHandshake = e.getMessage
      val (successEvent, serverResponse) = determineResponse(ctx, clientHandshake)
      val ip = e.getChannel.getRemoteAddress
      if (successEvent.isEmpty) {
        log.warning("%s: User not authenticated", ip)
        handshakeFailed(ctx)
        writeDownstream(ctx, channelClosingFuture, serverResponse)
        return
      }

      // Success! Change the pipeline for authenticated connections, and send the authenticated
      // response.
      log.info("%s: User authenticated", ip)
      val pipeline = ctx.getPipeline
      pipeline.remove("handshakeIdleHandler")
      // Flush any pending messages (no messages should be queued
      // because the server does not take the initiative
      // of sending messages to clients on its own...)
      for (message <- messages) {
        ctx.sendDownstream(message)
      }
      // Finally, remove this handler from the pipeline and fire success
      // event up the pipeline.
      handshakeSucceeded(ctx, successEvent.get)
      writeDownstream(ctx, serverResponse)
      pipeline.remove(this)
    }
  }

  override def channelConnected(ctx:ChannelHandlerContext, e:ChannelStateEvent) = {
    ServerHandshakeHandler.unauthenticatedChannels.add(ctx.getChannel)
    log.info("Client connected for handshake from %s", e.getChannel.getRemoteAddress)
  }

  override def channelClosed(ctx:ChannelHandlerContext, e:ChannelStateEvent) = {
    log.info("Channel closed")
    ServerHandshakeHandler.unauthenticatedChannels.remove(ctx.getChannel)
    if (!handshakeComplete.get) {
      handshakeFailed(ctx)
    }
  }

  override def exceptionCaught(ctx:ChannelHandlerContext, e:ExceptionEvent) = {
    log.warning(e.getCause, "Exception caught")
    if (e.getChannel().isConnected) {
      // Closing the channel will trigger handshake failure.
      e.getChannel.close
    } else {
      // Channel didn't open, so we must fire handshake failure directly.
      handshakeFailed(ctx)
    }
  }

  override def writeRequested(ctx:ChannelHandlerContext, e:MessageEvent):Unit = {
    // Before doing anything, ensure that noone else is working by
    // acquiring a lock on the handshakeMutex.
    handshakeMutex.synchronized {
      if (handshakeFailed.get) {
        // If the handshake failed meanwhile, discard any messages.
        return
      }

      // If the handshake hasn't failed but completed meanwhile and
      // messages still passed through this handler, then forward
      // them downwards.
      if (handshakeComplete.get) {
        log.warning("Handshake already completed, not appending %s to queue", e.getMessage.toString)
        super.writeRequested(ctx, e)
      } else {
        // Otherwise, queue messages in order until the handshake completes.
        messages.offer(e)
      }
    }
  }

  // private helpers --------------------------------------------------------

  private def writeDownstream(ctx:ChannelHandlerContext, f:ChannelFuture, data:Object):Unit = {
    val address = ctx.getChannel.getRemoteAddress
    val c = ctx.getChannel
    ctx.sendDownstream(new DownstreamMessageEvent(c, f, data, address))
  }

  private def writeDownstream(ctx:ChannelHandlerContext, data:Object):Unit = {
    val f = Channels.succeededFuture(ctx.getChannel)
    val address = ctx.getChannel.getRemoteAddress
    val c = ctx.getChannel
    ctx.sendDownstream(new DownstreamMessageEvent(c, f, data, address))
  }

  private def handshakeFailed(ctx:ChannelHandlerContext):Unit = {
    handshakeComplete.set(true)
    handshakeFailed.set(true)
    latch.countDown
  }

  private def closeChannel(ctx:ChannelHandlerContext):Unit = {
    ctx.getChannel.close
    ServerHandshakeHandler.unauthenticatedChannels.remove(ctx.getChannel)
  }

  private def handshakeSucceeded(ctx:ChannelHandlerContext, e:ServerHandshakeSuccessfulEvent):Unit = {
    handshakeComplete.set(true)
    handshakeFailed.set(false)
    latch.countDown
    ctx.sendUpstream(e)
    ServerHandshakeHandler.unauthenticatedChannels.remove(ctx.getChannel)
  }
}
