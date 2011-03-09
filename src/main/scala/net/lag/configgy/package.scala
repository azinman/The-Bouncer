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

package net

package object lag {
  // Applies a function to a value and then returns the value.
  def returning[T](x: T)(f: T => Unit): T = { f(x) ; x }
  
  // Returns list of dotted segments.
  def dotSegments(s: String): List[String] = if (s == null) Nil else s.split("\\.").toList
}