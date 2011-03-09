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
import java.text.SimpleDateFormat
import net.lag.extensions._


private[logging] object Syslog {
  val DEFAULT_PORT = 514

  val PRIORITY_USER = 8
  val PRIORITY_DAEMON = 24
  val PRIORITY_LOCAL0 = 128
  val PRIORITY_LOCAL1 = 136
  val PRIORITY_LOCAL2 = 144
  val PRIORITY_LOCAL3 = 152
  val PRIORITY_LOCAL4 = 160
  val PRIORITY_LOCAL5 = 168
  val PRIORITY_LOCAL6 = 176
  val PRIORITY_LOCAL7 = 184

  private val SEVERITY_EMERGENCY = 0
  private val SEVERITY_ALERT = 1
  private val SEVERITY_CRITICAL = 2
  private val SEVERITY_ERROR = 3
  private val SEVERITY_WARNING = 4
  private val SEVERITY_NOTICE = 5
  private val SEVERITY_INFO = 6
  private val SEVERITY_DEBUG = 7

  /**
   * Convert the java/scala log level into its closest syslog-ng severity.
   */
  private[logging] def severityForLogLevel(level: Int): Int = {
    if (level >= Level.FATAL.value) {
      SEVERITY_ALERT
    } else if (level >= Level.CRITICAL.value) {
      SEVERITY_CRITICAL
    } else if (level >= Level.ERROR.value) {
      SEVERITY_ERROR
    } else if (level >= Level.WARNING.value) {
      SEVERITY_WARNING
    } else if (level >= Level.INFO.value) {
      SEVERITY_INFO
    } else {
      SEVERITY_DEBUG
    }
  }
}
