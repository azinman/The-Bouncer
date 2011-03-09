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

import scala.collection.JavaConversions._
import scala.collection.mutable
import java.util.Date
import java.util.concurrent._
import java.util.concurrent.atomic._
import com.twitter.json._
import org.scala_tools.time._
import org.scala_tools.time.Imports._
import org.jboss.netty.channel.Channel
import java.util.{UUID => JavaUUID}

import com.netcapital.thebouncer.protocol._
import com.netcapital.thebouncer.protocol.Handshake._

case class UserState(channel:Channel, uuid:JavaUUID)

object PingPongMessageFactory {
  def ping(pingId:Int, timestampUtc:Long) = {
    NormalMessage.newBuilder
        .setType(NormalMessage.Type.PING)
        .setPing(Ping.newBuilder
          .setPingId(pingId)
          .setTimestampUtc(timestampUtc)
          .build)
        .build
  }

  def pong(pingId:Int, receiveDelta:Int, timestampUtc:Long) = {
    NormalMessage.newBuilder
        .setType(NormalMessage.Type.PONG)
        .setPong(Pong.newBuilder
          .setPingId(pingId)
          .setReceiveDelta(receiveDelta)
          .setTimestampUtc(timestampUtc)
          .build)
        .build
  }
}
