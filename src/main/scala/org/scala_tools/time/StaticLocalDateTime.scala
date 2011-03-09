/**
 * Copyright 2009 Jorge Ortiz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 *
 **/
package org.scala_tools.time

import java.util.{Calendar, Date}
import org.joda.time._
import org.scala_tools.time.Implicits._

object StaticLocalDateTime extends StaticLocalDateTime

trait StaticLocalDateTime {
  type Property = LocalDateTime.Property
  
  def fromCalendarFields(calendar: Calendar) =
    LocalDateTime.fromCalendarFields(calendar)
  def fromDateFields(date: Date) =
    LocalDateTime.fromDateFields(date)

  def now        = new LocalDateTime

  def nextSecond = now.plusSeconds(1)
  def nextMinute = now.plusMinutes(1)
  def nextHour   = now.plusHours(1)
  def nextDay    = now.plusDays(1)
  def tomorrow   = now.plusDays(1)
  def nextWeek   = now.plusWeeks(1)
  def nextMonth  = now.plusMonths(1)
  def nextYear   = now.plusYears(1)

  def lastSecond = now.minusSeconds(1)
  def lastMinute = now.minusMinutes(1)
  def lastHour   = now.minusHours(1)
  def lastDay    = now.minusDays(1)
  def yesterday  = now.minusDays(1)
  def lastWeek   = now.minusWeeks(1)
  def lastMonth  = now.minusMonths(1)
  def lastYear   = now.minusYears(1)
}
