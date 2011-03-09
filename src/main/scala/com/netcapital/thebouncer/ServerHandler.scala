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
import java.util.concurrent.atomic._
import java.nio.charset.Charset
import java.util.{UUID => JavaUUID}

import org.jboss.netty.buffer.ChannelBuffer
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel.ChannelPipeline
import org.jboss.netty.channel.ChannelPipelineFactory
import org.jboss.netty.channel.Channel
import org.jboss.netty.channel.Channels
import org.jboss.netty.channel.ChannelEvent
import org.jboss.netty.channel.ChannelHandlerContext
import org.jboss.netty.channel.ChannelStateEvent
import org.jboss.netty.channel.ExceptionEvent
import org.jboss.netty.channel.MessageEvent
import org.jboss.netty.channel.SimpleChannelUpstreamHandler
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.channel.group._
import org.jboss.netty.handler.codec.protobuf._
import org.jboss.netty.handler.execution.ExecutionHandler
import org.jboss.netty.handler.execution.OrderedMemoryAwareThreadPoolExecutor
import org.jboss.netty.handler.timeout._
import org.jboss.netty.util.HashedWheelTimer
import org.jboss.netty.util._

import com.netcapital.thebouncer.protocol._
import com.netcapital.thebouncer.protocol.Handshake._
import com.netcapital.util._

import net.lag.configgy.Configgy
import org.scala_tools.time.Imports._
import com.twitter.json._
import com.google.protobuf.GeneratedMessage

// EVENTS ------------------------------------------------------------------------------------------

class MyServerHandshakeSuccessfulEvent(val _channel:Channel, val uuid:JavaUUID) extends ServerHandshakeSuccessfulEvent(_channel)

// HANDSHAKE ---------------------------------------------------------------------------------------

object MyServerHandshakeHandler {
  val MINIMUM_VERSION = 100
}

class MyServerHandshakeHandler extends ServerHandshakeHandler {
  /**
   * Template for checking credentials in DB
   */
  def authenticateUser(clientHandshake:ClientHandshake):Tuple2[Option[JavaUUID], Option[String]] = {
    log.info("Trying to authenticate user")
    // Implement authentication here
    // val uuid = checkInDb(clientHandshake)
    // return (Some(uuid), None)
    return (None, Some("Function not implemented"))
  }

  /**
   * Template for creating new users
   */
  def createUser(clientHandshake:ClientHandshake):Tuple2[Option[JavaUUID], Option[String]] = {
    (None, Some("Function not implemented"))
  }

  def forceUpdate(clientHandshake:ClientHandshake):Tuple2[Boolean, Option[String]] = {
    if (clientHandshake.getVersion < MyServerHandshakeHandler.MINIMUM_VERSION) {
      return (true, None)
    }
    return (false, None)
  }

  def getAlertMessage(clientHandshake:ClientHandshake):Option[String] = {
    return None
  }

  override def determineResponse(ctx:ChannelHandlerContext, clientHandshakeMsg:Object):Tuple2[Option[ServerHandshakeSuccessfulEvent], GeneratedMessage] = {
    val clientHandshake = clientHandshakeMsg.asInstanceOf[ClientHandshake]
    val responseBuilder = Handshake.ServerHandshakeResponse.newBuilder()
    val (needsUpdate, updateReasonOpt) = forceUpdate(clientHandshake)
    var successEvent:Option[MyServerHandshakeSuccessfulEvent] = None
    responseBuilder.setForceUpdate(needsUpdate)
    if (needsUpdate) {
      if (updateReasonOpt.isDefined) {
        responseBuilder.setForceUpdateReason(updateReasonOpt.get)
      }
    } else {
      val (uuidOpt, failureReasonOpt) = clientHandshake.getNewUser match {
        case false => authenticateUser(clientHandshake)
        case true => createUser(clientHandshake)
      }
      responseBuilder.setAuthenticated(uuidOpt.isDefined)
      if (failureReasonOpt.isDefined) {
        responseBuilder.setFailureReason(failureReasonOpt.get)
      }
      if (uuidOpt.isDefined) {
        successEvent = Some(new MyServerHandshakeSuccessfulEvent(ctx.getChannel, uuidOpt.get))
        val alertMessageOpt = getAlertMessage(clientHandshake)
        if (alertMessageOpt.isDefined) {
          responseBuilder.setAlertMessage(alertMessageOpt.get)
        }
      }
    }
    return (successEvent, responseBuilder.build)
  }
}

// SERVER (CHANNEL LOGIC, MESSAGE ROUTING) ---------------------------------------------------------

object MyServerHandler {
  val authenticatedChannels = new DefaultChannelGroup()
}

trait StatefulHandler extends Logging {
  var state:UserState = null
}

trait MyServerLogic extends StatefulHandler {
  def processJson(state:UserState, json:String):Option[NormalMessage] = {
    throw new Exception("Just an example function -- implement your own server logic")
  }
}

class MyServerHandler extends IdleStateAwareChannelUpstreamHandler with MyServerLogic
                                                                   with PingPong
                                                                   with StatefulHandler
                                                                   with Logging {
  /**
   * Handle the following upstream events first:
   * 1. Successful handshake (login): Initialize world state and modify the pipeline
   * 2. ??? (implement yourself)
   */
  override def handleUpstream(ctx:ChannelHandlerContext, ev:ChannelEvent):Unit = {
    if (ev.isInstanceOf[MyServerHandshakeSuccessfulEvent]) {
      val e = ev.asInstanceOf[MyServerHandshakeSuccessfulEvent]
      log.info("Logging in UUID %s", e.uuid)
      state = UserState(ctx.getChannel, e.uuid)
      ctx.getPipeline.replace("protobufDecoder", "protobufDecoder",
        new ProtobufDecoder(Handshake.NormalMessage.getDefaultInstance()))
      MyServerHandler.authenticatedChannels.add(ctx.getChannel)

      // Implement your new user logic here
      throw new Exception("New user logic not implemented in MyServerHandler")

      return
    }
    super.handleUpstream(ctx, ev)
  }

  override def channelIdle(ctx:ChannelHandlerContext, e:IdleStateEvent) = {
    // Send keep alive ping
    if (e.getState == IdleState.READER_IDLE) {
      sendPing(ctx.getChannel)
    }
  }

  override def messageReceived(ctx:ChannelHandlerContext, e:MessageEvent) = {
    if (state == null) {
      throw new Exception("Received message without having any state yet")
    }

    val msg = e.getMessage.asInstanceOf[NormalMessage]
    var response:Option[NormalMessage] = None
    try {
      response = msg.getType match {
        case NormalMessage.Type.PING => processPing(ctx.getChannel, msg.getPing)
        case NormalMessage.Type.PONG => processPong(msg.getPong)
        case NormalMessage.Type.JSON => processJson(state, msg.getJsonData)
      }
    } catch {
      case e:Throwable =>
        log.error(e, "Exception occurred while processing user request")
        response = None
    }

    response match {
      case None =>
      case Some(r:NormalMessage) => state.channel.write(r)
    }
  }

  override def exceptionCaught(ctx:ChannelHandlerContext, e:ExceptionEvent) = {
    // Close the connection when an exception is raised.
    log.warning(e.getCause, "Unexpected exception from downstream: %s", e.getCause.getMessage)
    ctx.getChannel.close
  }

  override def channelClosed(ctx:ChannelHandlerContext, e:ChannelStateEvent) = {
    super.channelClosed(ctx, e)
    MyServerHandler.authenticatedChannels.remove(ctx.getChannel)
    state = null
    if (pingTimeout != null) pingTimeout.cancel
    pingTimeout = null
    throw new Exception("User disconnect not implemented in MyServerHandler")
  }
}

trait PingPong extends StatefulHandler with Logging {
  var sentPing:Ping = null
  var pingTimeout:Timeout = null

  def mkTimeoutTask(channel:Channel) = new TimerTask() {
    def run(timeout:Timeout) = {
      channel.close
      log.info("Player timed out: %s", state)
    }
  }

  def sendPing(channel:Channel) = {
    val now = DateTime.now.getMillis
    val serverMessage = PingPongMessageFactory.ping(Unique.nextInteger, now)
    channel.write(serverMessage)
    sentPing = serverMessage.getPing
    pingTimeout = Server.timer.newTimeout(mkTimeoutTask(channel), 60, TimeUnit.SECONDS)
  }

  def processPong(pong:Pong):Option[NormalMessage] = {
    if (pingTimeout != null) {
      pingTimeout.cancel
      pingTimeout = null
    }
    val now = DateTime.now.getMillis
    val approxFirstHalf = pong.getReceiveDelta
    val approxSecondHalf = now - pong.getTimestampUtc
    val roundtrip = now - sentPing.getTimestampUtc
    log.info("Got back pong, approx %dmsec then %dmsec. rt is %dmsec",
             approxFirstHalf, approxSecondHalf, roundtrip)
    None
  }

  def processPing(channel:Channel, ping:Ping):Option[NormalMessage] = {
    val now = DateTime.now.getMillis
    val delta:Int = (now - ping.getTimestampUtc).asInstanceOf[Int]
    val serverMessage = PingPongMessageFactory.pong(ping.getPingId, delta, now)
    log.info("Got ping id %d, first half delta is estimated %dmsec", ping.getPingId, delta)
    channel.write(serverMessage)
    None
  }
}
