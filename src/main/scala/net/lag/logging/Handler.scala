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
import scala.collection.mutable
import net.lag.extensions._


/**
 * A base log handler for scala. This extends the java built-in handler
 * and connects it with a formatter automatically.
 */
abstract class Handler(_formatter: Formatter) extends javalog.Handler {
  type FormatterType <: Formatter

  setFormatter(_formatter)


  /**
   * Where to truncate log messages (character count). 0 = don't truncate.
   */
  def truncateAt = formatter.truncateAt

  /**
   * Where to truncate log messages (character count). 0 = don't truncate.
   */
  def truncateAt_=(n: Int) = {
    formatter.truncateAt = n
  }

  /**
   * Where to truncate stack traces in exception logging (line count).
   */
  def truncateStackTracesAt = formatter.truncateStackTracesAt

  /**
   * Where to truncate stack traces in exception logging (line count).
   */
  def truncateStackTracesAt_=(n: Int) = {
    formatter.truncateStackTracesAt = n
  }

  /**
   * Return <code>true</code> if dates in log messages are being reported
   * in UTC time, or <code>false</code> if they're being reported in local
   * time.
   */
  def useUtc = formatter.useUtc

  /**
   * Set whether dates in log messages should be reported in UTC time
   * (<code>true</code>) or local time (<code>false</code>, the default).
   * This variable and <code>timeZone</code> affect the same settings, so
   * whichever is called last will take precedence.
   */
  def useUtc_=(utc: Boolean) = formatter.useUtc = utc

  /**
   * Return the formatter associated with this log handler.
   */
  def formatter: FormatterType = getFormatter.asInstanceOf[FormatterType]

  override def toString = {
    "<%s level=%s utc=%s truncate=%d truncate_stack=%d>".format(getClass.getName, getLevel,
      if (useUtc) "true" else "false", truncateAt, truncateStackTracesAt)
  }
}
