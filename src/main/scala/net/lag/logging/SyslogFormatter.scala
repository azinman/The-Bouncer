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

package net.lag
package logging

import java.util.{logging => javalog}
import java.net.{DatagramPacket, DatagramSocket, InetAddress, InetSocketAddress, SocketAddress}
import java.text.SimpleDateFormat
import net.lag.extensions._

class SyslogFormatter(useIsoDateFormat: Boolean) extends Formatter {
  private val ISO_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
  private val OLD_SYSLOG_DATE_FORMAT = new SimpleDateFormat("MMM dd HH:mm:ss")

  // user may override:
  var priority = Syslog.PRIORITY_USER

  // user may override:
  var hostname = InetAddress.getLocalHost().getHostName()

  private var _serverName: Option[String] = None
  def serverName = _serverName match {
    case None => ""
    case Some(name) => name
  }
  def serverName_=(name: String) {
    _serverName = Some(name)
  }
  def clearServerName = {
    _serverName = None
  }

  override def dateFormat = if (useIsoDateFormat) ISO_DATE_FORMAT else OLD_SYSLOG_DATE_FORMAT
  override def lineTerminator = ""

  override def formatPrefix(level: javalog.Level, date: String, name: String): String = {
    val syslogLevel = level match {
      case Level(name, x) => Syslog.severityForLogLevel(x)
      case x: javalog.Level => Syslog.severityForLogLevel(x.intValue)
    }
    _serverName match {
      case None => "<%d>%s %s %s: ".format(priority | syslogLevel, date, hostname, name)
      case Some(serverName) => "<%d>%s %s [%s] %s: ".format(priority | syslogLevel, date, hostname, serverName, name)
    }
  }
}
