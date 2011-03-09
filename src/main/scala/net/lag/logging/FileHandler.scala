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

import java.io.{File, FileOutputStream, OutputStreamWriter, Writer}
import java.text.SimpleDateFormat
import java.util.{Calendar, Date, logging => javalog}

/**
 * A log handler that writes log entries into a file, and rolls this file
 * at a requested interval (hourly, daily, or weekly).
 */
class FileHandler(val filename: String, val policy: Policy, formatter: Formatter,
                  val append: Boolean) extends Handler(formatter) {

  private var stream: Writer = null
  private var openTime: Long = 0
  private var nextRollTime: Long = 0
  openLog()

  def flush() = {
    stream.flush()
  }

  def close() = {
    flush()
    try stream.close()
    catch { case _ => () }
  }

  private def openLog() = {
    val dir = new File(filename).getParentFile
    if ((dir ne null) && !dir.exists) dir.mkdirs
    stream = new OutputStreamWriter(new FileOutputStream(filename, append), "UTF-8")
    openTime = System.currentTimeMillis
    nextRollTime = computeNextRollTime()
  }

  /**
   * Compute the suffix for a rolled logfile, based on the roll policy.
   */
  def timeSuffix(date: Date) = {
    val dateFormat = new SimpleDateFormat(policy match {
      case Never    => "yyyy"
      case Hourly   => "yyyyMMdd-HH"
      case _        => "yyyyMMdd"
    })
    dateFormat.setCalendar(formatter.calendar)
    dateFormat.format(date)
  }

  /**
   * Return the time (in absolute milliseconds) of the next desired
   * logfile roll.
   */
  def computeNextRollTime(now: Long): Long = {
    import Calendar._
    val next = formatter.calendar.clone.asInstanceOf[Calendar]
    next.setTimeInMillis(now)
    next.set(MILLISECOND, 0)
    next.set(SECOND, 0)
    next.set(MINUTE, 0)
    policy match {
      case Never =>
        next.add(YEAR, 100)
      case Hourly =>
        next.add(HOUR_OF_DAY, 1)
      case Daily =>
        next.set(HOUR_OF_DAY, 0)
        next.add(DAY_OF_MONTH, 1)
      case Weekly(weekday) =>
        next.set(HOUR_OF_DAY, 0)
        do {
          next.add(DAY_OF_MONTH, 1)
        } while (next.get(DAY_OF_WEEK) != weekday)
    }
    next.getTimeInMillis
  }

  def computeNextRollTime(): Long = computeNextRollTime(System.currentTimeMillis)

  private def roll() = {
    stream.close()
    val n = (filename lastIndexOf '.') match { case -1 => filename.length ; case x => x }
    val newFilename = (filename take n) + "-" + timeSuffix(new Date(openTime)) + (filename drop n)
    
    new File(filename) renameTo new File(newFilename)
    openLog()
  }

  def publish(record: javalog.LogRecord) = synchronized {
    try {
      if (System.currentTimeMillis > nextRollTime) {
        roll
      }
      stream.write(getFormatter.format(record))
      stream.flush
    } catch {
      case e =>
        System.err.println(Formatter.formatStackTrace(e, 30).mkString("\n"))
    }
  }
}
