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

import java.util.{logging => javalog}
import java.net.{DatagramPacket, DatagramSocket, InetAddress, InetSocketAddress, SocketAddress}
import net.lag.extensions._

class SyslogHandler(useIsoDateFormat: Boolean, server: String) extends Handler(new SyslogFormatter(useIsoDateFormat)) {
  override type FormatterType = SyslogFormatter
  
  private val socket = new DatagramSocket
  private[logging] val dest: SocketAddress = server.split(":", 2).toList match {
    case host :: port :: Nil => new InetSocketAddress(host, port.toInt)
    case host :: Nil => new InetSocketAddress(host, Syslog.DEFAULT_PORT)
    case _ => null
  }

  def flush() = { }
  def close() = { }

  def priority = formatter.priority
  def priority_=(priority: Int) = {
    formatter.priority = priority
  }

  def serverName = formatter.serverName
  def serverName_=(name: String) = {
    formatter.serverName = name
  }
  def clearServerName = formatter.clearServerName

  def publish(record: javalog.LogRecord) = synchronized {
    try {
      val data = getFormatter.format(record).getBytes
      val packet = new DatagramPacket(data, data.length, dest)
      socket.send(packet)
    } catch {
      case e =>
        System.err.println(Formatter.formatStackTrace(e, 30).mkString("\n"))
    }
  }
}
