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

import java.text.SimpleDateFormat
import java.util.{Date, GregorianCalendar, TimeZone, logging => javalog}
import java.util.regex.Pattern
import scala.collection.mutable
import net.lag.extensions._


private[logging] object Formatter {
  // FIXME: might be nice to unmangle some scala names here.
  private[logging] def formatStackTrace(t: Throwable, limit: Int): mutable.ArrayBuffer[String] = {
    var out = new mutable.ArrayBuffer[String]
    out ++= (for (elem <- t.getStackTrace) yield "    at %s".format(elem.toString))
    if (out.length > limit) {
      out = new mutable.ArrayBuffer[String] ++= out.take(limit)
      out += "    (...more...)"
    }
    if (t.getCause ne null) {
      out += "Caused by %s".format(t.getCause.toString)
      out ++= formatStackTrace(t.getCause, limit)
    }
    out
  }
}


/**
 * A standard log formatter for scala. This extends the java built-in
 * log formatter.
 *
 * Truncation, exception formatting, multi-line logging, and time zones
 * are handled in this class. Subclasses are called for formatting the
 * line prefix, formatting the date, and determining the line terminator.
 *
 */
abstract class Formatter extends javalog.Formatter {

  /**
   * Where to truncate log messages (character count). 0 = don't truncate.
   */
  var truncateAt: Int = 0

  /**
   * Where to truncate stack traces in exception logging (line count).
   */
  var truncateStackTracesAt: Int = 30

  /**
   * Whether to use full package names like "com.example.thingy" or (the default) just the
   * toplevel like "thingy".
   */
  var useFullPackageNames = false
  var stripPackageName:Option[String] = None

  private var _useUtc = false

  /**
   * Calendar to use for time zone display in date-time formatting.
   */
  var calendar = new GregorianCalendar

  /**
   * Return <code>true</code> if dates in log messages are being reported
   * in UTC time, or <code>false</code> if they're being reported in local
   * time.
   */
  def useUtc = _useUtc

  private def calendarForZone(name: String) =
    new GregorianCalendar(TimeZone getTimeZone name)

  /**
   * Set whether dates in log messages should be reported in UTC time
   * (<code>true</code>) or local time (<code>false</code>, the default).
   * This variable and <code>timeZone</code> affect the same settings, so
   * whichever is called last will take precedence.
   */
  def useUtc_=(utc: Boolean) = {
    _useUtc = utc
    calendar = if (utc) calendarForZone("UTC") else new GregorianCalendar

    dateFormat setCalendar calendar
  }

  /**
   * Return the name of the time zone currently used for formatting dates
   * in log messages. Normally this will either be the local time zone or
   * UTC (if <code>use_utc</code> was set), but it can also be set
   * manually.
   */
  def timeZone = calendar.getTimeZone.getDisplayName

  /**
   * Set the time zone for formatting dates in log messages. The time zone
   * name must be one known by the java <code>TimeZone</code> class.
   */
  def timeZone_=(name: String) = {
    calendar = calendarForZone(name)
    dateFormat setCalendar calendar
  }

  /**
   * Return the date formatter to use for log messages.
   */
  def dateFormat: SimpleDateFormat

  /**
   * Return the line terminator (if any) to use at the end of each log
   * message.
   */
  def lineTerminator: String

  /**
   * Return the string to prefix each log message with, given a log level,
   * formatted date string, and package name.
   */
  def formatPrefix(level: javalog.Level, date: String, name: String): String

  override def format(record: javalog.LogRecord): String = {
    val name = record.getLoggerName match {
      case "" => "(root)"
      case n  =>
        if (stripPackageName.isDefined && n.startsWith(stripPackageName.get)) {
          n.substring(stripPackageName.get.length + 1)
        } else {
          val nameSegments = n.split("\\.")
          if (nameSegments.length >= 2) {
            if (useFullPackageNames) {
              nameSegments.slice(0, nameSegments.length - 1).mkString(".")
            } else {
              nameSegments(nameSegments.length - 2)
            }
          } else {
            n
          }
        }
    }

    val message: String = {
      val m = record match {
        case r: LazyLogRecord                                 => r.generate.toString
        case r: javalog.LogRecord if r.getParameters == null  => r.getMessage
        case r: javalog.LogRecord                             => String.format(r.getMessage, r.getParameters: _*)
      }
      if (truncateAt <= 0 || m.length <= truncateAt) m
      else (m take truncateAt) + "..."
    }

    // allow multi-line log entries to be atomic:
    var lines = new mutable.ArrayBuffer[String]
    lines ++= message.split("\n")

    if (record.getThrown ne null) {
      lines += record.getThrown.toString
      lines ++= Formatter.formatStackTrace(record.getThrown, truncateStackTracesAt)
    }
    val prefix = formatPrefix(record.getLevel, dateFormat.format(new Date(record.getMillis)), name)
    lines.mkString(prefix, lineTerminator + prefix, lineTerminator)
  }
}


/**
 * A log formatter that takes a format string containing a date formatter and
 * positions for the level name and logger name, and uses that to generate the
 * log line prefix.
 *
 * The date format should be between `<` angle brackets `>` and be a string
 * that can be passed to java's `SimpleDateFormat`. The rest of the format
 * string will have java's C-like `format()` called on it, with the level name
 * as the first positional parameter, and the logger name as the second.
 *
 * For example, a format string of:
 *
 *     "%.3s [<yyyyMMdd-HH:mm:ss.SSS>] %s: "
 *
 * will generate a log line prefix of:
 *
 *     "ERR [20080315-18:39:05.033] julius: "
 */
class GenericFormatter(format: String) extends Formatter {
  private val dateFormatRegex = Pattern.compile("<([^>]+)>")
  private val matcher = dateFormatRegex.matcher(format)

  private val DATE_FORMAT = new SimpleDateFormat(if (matcher.find()) matcher.group(1) else "yyyyMMdd-HH:mm:ss.SSS")
  private val FORMAT = matcher.replaceFirst("%3\\$s")

  override def dateFormat = DATE_FORMAT
  override def lineTerminator = "\n"

  override def formatPrefix(level: javalog.Level, date: String, name: String): String = {
    // if it maps to one of our levels, use our name.
    val levelName = level match {
      case Level(name, _) => name
      case x              => Logger.levelsMap get x.intValue map (_.name) getOrElse ("%03d" format x.intValue)
    }
    FORMAT.format(levelName, name, date)
  }
}


/**
 * The standard log formatter for a logfile. Log entries are written in this format:
 *
 *     ERR [20080315-18:39:05.033] julius: et tu, brute?
 *
 * which indicates the level (error), the date/time, the logger's name
 * (julius), and the message. The logger's name is usually also the
 * last significant segment of the package name (ie "com.lag.julius"),
 * although packages can override this.
 */
class FileFormatter extends GenericFormatter("%.3s [<yyyyMMdd-HH:mm:ss.SSS>] %s: ")
