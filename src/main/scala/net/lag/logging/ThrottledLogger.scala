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

import _root_.scala.collection.mutable

class ThrottledLogger[T](wrapped: Logger, durationMilliseconds: Int, maxToDisplay: Int) {
  private class Throttle(now: Long) {
    var startTime: Long = now
    var count: Int = 0
  }

  private val throttleMap = new mutable.HashMap[T, Throttle]

  def reset(): Unit = throttleMap.synchronized {
    throttleMap.valuesIterator foreach (_.startTime = 0)
  }

  /**
   * Log a message, with sprintf formatting, at the desired level, and
   * attach an exception and stack trace.
   */
  def log(level: Level, key: T, thrown: Throwable, message: String, items: Any*) {
    val myLevel = wrapped.getLevel
    if ((myLevel eq null) || (level.intValue >= myLevel.intValue)) {
      val now = System.currentTimeMillis
      val throttle = throttleMap.synchronized { throttleMap.getOrElseUpdate(key, new Throttle(now)) }
      throttle.synchronized {
        if (now - throttle.startTime >= durationMilliseconds) {
          if (throttle.count > maxToDisplay) {
            wrapped.log(level, null: Throwable, "(swallowed %d repeating messages)", throttle.count - maxToDisplay)
          }
          throttle.startTime = now
          throttle.count = 0
        }
        throttle.count += 1
        if (throttle.count <= maxToDisplay) {
          wrapped.log(level, thrown, message, items: _*)
        }
      }
    }
  }

  def log(level: Level, key: T, msg: String, items: Any*): Unit = log(level, key, null: Throwable, msg, items: _*)

  // convenience methods:
  def fatal(key: T, msg: String, items: Any*) = log(Level.FATAL, key, msg, items: _*)
  def fatal(key: T, thrown: Throwable, msg: String, items: Any*) = log(Level.FATAL, key, thrown, msg, items: _*)
  def critical(key: T, msg: String, items: Any*) = log(Level.CRITICAL, key, msg, items: _*)
  def critical(key: T, thrown: Throwable, msg: String, items: Any*) = log(Level.CRITICAL, key, thrown, msg, items: _*)
  def error(key: T, msg: String, items: Any*) = log(Level.ERROR, key, msg, items: _*)
  def error(key: T, thrown: Throwable, msg: String, items: Any*) = log(Level.ERROR, key, thrown, msg, items: _*)
  def warning(key: T, msg: String, items: Any*) = log(Level.WARNING, key, msg, items: _*)
  def warning(key: T, thrown: Throwable, msg: String, items: Any*) = log(Level.WARNING, key, thrown, msg, items: _*)
  def info(key: T, msg: String, items: Any*) = log(Level.INFO, key, msg, items: _*)
  def info(key: T, thrown: Throwable, msg: String, items: Any*) = log(Level.INFO, key, thrown, msg, items: _*)
  def debug(key: T, msg: String, items: Any*) = log(Level.DEBUG, key, msg, items: _*)
  def debug(key: T, thrown: Throwable, msg: String, items: Any*) = log(Level.DEBUG, key, thrown, msg, items: _*)
  def trace(key: T, msg: String, items: Any*) = log(Level.TRACE, key, msg, items: _*)
  def trace(key: T, thrown: Throwable, msg: String, items: Any*) = log(Level.TRACE, key, thrown, msg, items: _*)
}
