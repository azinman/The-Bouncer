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

package net.lag.configgy

/**
 * Key returned by a call to <code>AttributeMap.subscribe</code> which may
 * be used to unsubscribe from config change events.
 */
class SubscriptionKey private[configgy](val config: Config, private[configgy] val id: Int) {
  /**
   * Remove the subscription referenced by this key. After unsubscribing,
   * no more validate/commit events will be sent to this subscriber.
   */
  def unsubscribe() = config.unsubscribe(this)
}
