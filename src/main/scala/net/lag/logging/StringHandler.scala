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
 * Mostly useful for unit tests: logging goes directly into a
 * string buffer.
 */
class StringHandler(_formatter: Formatter) extends Handler(_formatter) {
  private var buffer = new StringBuilder()

  def publish(record: javalog.LogRecord) = {
    buffer append getFormatter().format(record)
  }

  def close() = { }

  def flush() = { }

  override def toString = buffer.toString

  def clear() = {
    buffer.clear
  }
}
