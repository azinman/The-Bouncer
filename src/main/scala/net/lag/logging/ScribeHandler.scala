/*
 * Copyright 2009 Robey Pointer <robeypointer@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.lag.logging

import _root_.java.io.IOException
import _root_.java.net._
import _root_.java.nio.{ByteBuffer, ByteOrder}
import _root_.java.util.{Arrays, logging => javalog}
import _root_.scala.collection.mutable

private class Retry extends Exception("retry")

object ScribeHandler {
  val OK = 0
  val TRY_LATER = 1
}

class ScribeHandler(formatter: Formatter) extends Handler(formatter) {
  // it may be necessary to log errors here if scribe is down:
  val log = Logger.get("scribe")

  // send a scribe message no more frequently than this:
  var bufferTimeMilliseconds = 100
  var lastTransmission: Long = 0

  // don't connect more frequently than this (when the scribe server is down):
  var connectBackoffMilliseconds = 15000
  var lastConnectAttempt: Long = 0

  var maxMessagesPerTransaction = 1000
  var maxMessagesToBuffer = 10000

  var hostname = "localhost"
  var port = 1463
  var category = "scala"

  var socket: Option[Socket] = None
  val queue = new mutable.ArrayBuffer[String]

  var archaicServer = false

  def server_=(server: String): Unit = server.split(":", 2) match {
    case Array(h)     => hostname = h
    case Array(h, p)  => hostname = h ; port = p.toInt
  }

  def server = "%s:%d".format(hostname, port)

  private def connect() {
    if (!socket.isDefined && (System.currentTimeMillis - lastConnectAttempt > connectBackoffMilliseconds)) {
      lastConnectAttempt = System.currentTimeMillis
      try socket = Some(new Socket(hostname, port))
      catch {
        case e: Exception =>
          log.error("Unable to open socket to scribe server at %s: %s", server, e)
      }
    }
  }

  def flush(): Unit = synchronized {
    connect()
    for (s <- socket) {
      val outStream = s.getOutputStream()
      val inStream = s.getInputStream()
      val count = maxMessagesPerTransaction min queue.size
      val buffer = makeBuffer(count)

      try {
        outStream.write(buffer.array)
        val expectedReply = if (archaicServer) OLD_SCRIBE_REPLY else SCRIBE_REPLY

        // read response:
        val response = new Array[Byte](expectedReply.length)
        var offset = 0
        while (offset < response.length) {
          val n = inStream.read(response, offset, response.length - offset)
          if (n < 0) {
            throw new IOException("End of stream")
          }
          offset += n
          if (!archaicServer && (offset > 0) && (response(0) == 0)) {
            archaicServer = true
            close()
            lastConnectAttempt = 0
            log.error("Scribe server is archaic; retrying with old protocol.")
            throw new Retry
          }
        }
        if (!Arrays.equals(response, expectedReply)) {
          throw new IOException("Error response from scribe server: " + response.toList.toString)
        }
        queue.trimStart(count)
        if (queue.isEmpty) {
          lastTransmission = System.currentTimeMillis
        }
      } catch {
        case _: Retry =>
          flush()
        case e: Exception =>
          log.error(e, "Failed to send %d log entries to scribe server at %s", count, server)
          close()
      }
    }
  }

  def makeBuffer(count: Int): ByteBuffer = {
    val texts = 0 until count map (i => queue(i) getBytes "UTF-8")

    val recordHeader = ByteBuffer.wrap(new Array[Byte](10 + category.length))
    recordHeader.order(ByteOrder.BIG_ENDIAN)
    recordHeader.put(11: Byte)
    recordHeader.putShort(1)
    recordHeader.putInt(category.length)
    recordHeader.put(category.getBytes("ISO-8859-1"))
    recordHeader.put(11: Byte)
    recordHeader.putShort(2)

    val prefix = if (archaicServer) OLD_SCRIBE_PREFIX else SCRIBE_PREFIX
    val messageSize = (count * (recordHeader.capacity + 5)) + texts.foldLeft(0) { _ + _.length } + prefix.length + 5
    val buffer = ByteBuffer.wrap(new Array[Byte](messageSize + 4))
    buffer.order(ByteOrder.BIG_ENDIAN)
    // "framing":
    buffer.putInt(messageSize)
    buffer.put(prefix)
    buffer.putInt(count)
    for (text <- texts) {
      buffer.put(recordHeader.array)
      buffer.putInt(text.length)
      buffer.put(text)
      buffer.put(0: Byte)
    }
    buffer.put(0: Byte)
    buffer
  }

  def close(): Unit = synchronized {
    try socket map (_.close())
    catch { case _ => }

    socket = None
  }

  def publish(record: javalog.LogRecord): Unit = synchronized {
    if (record.getLoggerName == "scribe") return
    queue += getFormatter.format(record)
    while (queue.size > maxMessagesToBuffer) {
      queue.trimStart(1)
    }
    if (System.currentTimeMillis - lastTransmission >= bufferTimeMilliseconds) {
      flush()
    }
  }

  override def toString =
    ("<%s level=%s utc=%s truncate=%d truncate_stack=%d server=%s scribe_buffer_msec=%d " +
     "scribe_backoff_msec=%d scribe_max_packet_size=%d>").format(
       getClass.getName, getLevel, useUtc, truncateAt, truncateStackTracesAt, server,
       bufferTimeMilliseconds, connectBackoffMilliseconds, maxMessagesPerTransaction
    )

  val SCRIBE_PREFIX: Array[Byte] = Array(
    // version 1, call, "Log", reqid=0
    0x80.toByte, 1, 0, 1, 0, 0, 0, 3, 'L'.toByte, 'o'.toByte, 'g'.toByte, 0, 0, 0, 0,
    // list of structs
    15, 0, 1, 12
  )
  val OLD_SCRIBE_PREFIX: Array[Byte] = Array(
    // (no version), "Log", reply, reqid=0
    0, 0, 0, 3, 'L'.toByte, 'o'.toByte, 'g'.toByte, 1, 0, 0, 0, 0,
    // list of structs
    15, 0, 1, 12
  )

  val SCRIBE_REPLY: Array[Byte] = Array(
    // version 1, reply, "Log", reqid=0
    0x80.toByte, 1, 0, 2, 0, 0, 0, 3, 'L'.toByte, 'o'.toByte, 'g'.toByte, 0, 0, 0, 0,
    // int, fid 0, 0=ok
    8, 0, 0, 0, 0, 0, 0, 0
  )
  val OLD_SCRIBE_REPLY: Array[Byte] = Array(
    0, 0, 0, 20,
    // (no version), "Log", reply, reqid=0
    0, 0, 0, 3, 'L'.toByte, 'o'.toByte, 'g'.toByte, 2, 0, 0, 0, 0,
    // int, fid 0, 0=ok
    8, 0, 0, 0, 0, 0, 0, 0
  )
}
