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
package configgy

import java.io.File
import java.lang.management.ManagementFactory
import javax.{management => jmx}
import scala.collection.{ Map, Set, immutable, mutable }
import net.lag.extensions._
import net.lag.logging.Logger

private abstract class Phase
private case object VALIDATE_PHASE extends Phase
private case object COMMIT_PHASE extends Phase

private class SubscriptionNode {
  val subscribers = new mutable.HashSet[Subscriber]
  val map = new mutable.HashMap[String, SubscriptionNode]

  def get(name: String): SubscriptionNode = map.getOrElseUpdate(name, new SubscriptionNode)

  override def toString() = {
    val out = new StringBuilder("%d" format subscribers.size)
    if (map.size > 0) {
      out.append(" { ")
      map foreach { case (k, v) => out append "%s=%s ".format(k, v) }
      out.append("}")
    }
    out.toString
  }

  @throws(classOf[ValidationException])
  def validate(key: List[String], current: Option[ConfigMap], replacement: Option[ConfigMap], phase: Phase): Unit = {
    if ((current == None) && (replacement == None)) {
      // someone has subscribed to a nonexistent node... ignore.
      return
    }

    // first, call all subscribers for this node.
    
    for (subscriber <- subscribers) {
      phase match {
        case VALIDATE_PHASE => subscriber.validate(current, replacement)
        case COMMIT_PHASE => subscriber.commit(current, replacement)
      }
    }

    /* if we're walking a key, lookup the next segment's subscribers and
     * continue the validate/commit. if the key is exhausted, call
     * subscribers for ALL nodes below this one.
     */
    val nextNodes =
      if (key.isEmpty) map.toList
      else map.toList find (_._1 == key.head) toList

    for ((segment, node) <- nextNodes) {
      val subCurrent = current flatMap (_ getConfigMap segment)
      val subReplacement = replacement flatMap (_ getConfigMap segment)

      node.validate(key drop 1, subCurrent, subReplacement, phase)
    }
  }
}


/**
 * An attribute map of key/value pairs and subscriptions, where values may
 * be other attribute maps. Config objects represent the "root" of a nested
 * set of attribute maps, and control the flow of subscriptions and events
 * for subscribers.
 */
class Config extends ConfigMap {
  private var root = new Attributes(this, "")
  private val subscribers = new SubscriptionNode
  private val subscriberKeys = new mutable.HashMap[Int, (SubscriptionNode, Subscriber)]
  private var nextKey = 1

  private var jmxNodes: List[String] = Nil
  private var jmxPackageName: String = ""
  private var jmxSubscriptionKey: Option[SubscriptionKey] = None

  /**
   * Importer for resolving "include" lines when loading config files.
   * By default, it's a FilesystemImporter based on the current working
   * directory.
   */
  var importer: Importer = new FilesystemImporter(new File(".").getCanonicalPath)

  /**
   * Read config data from a string and use it to populate this object.
   */
  def load(data: String) = {
    val newRoot = new Attributes(this, "")
    new ConfigParser(newRoot, importer) parse data

    if (root.isMonitored) {
      // throws exception if validation fails:
      List(VALIDATE_PHASE, COMMIT_PHASE) foreach (p => subscribers.validate(Nil, Some(root), Some(newRoot), p))
    }

    if (root.isMonitored) newRoot.setMonitored
    root.replaceWith(newRoot)
  }

  /**
   * Read config data from a file and use it to populate this object.
   */
  def loadFile(filename: String) {
    load(importer.importFile(filename))
  }

  /**
   * Read config data from a file and use it to populate this object.
   */
  def loadFile(path: String, filename: String) {
    importer = new FilesystemImporter(path)
    loadFile(filename)
  }

  override def toString = root.toString

  // -----  subscriptions

  private[configgy] def subscribe(key: String, subscriber: Subscriber): SubscriptionKey = synchronized {
    root.setMonitored
    val subkey = nextKey
    nextKey += 1
    
    val node = dotSegments(key).foldLeft(subscribers)(_ get _)
    node.subscribers += subscriber
    
    subscriberKeys += Pair(subkey, (node, subscriber))
    new SubscriptionKey(this, subkey)
  }

  private[configgy] def subscribe(key: String)(f: (Option[ConfigMap]) => Unit): SubscriptionKey =
    subscribe(key, new Subscriber {
      def validate(current: Option[ConfigMap], replacement: Option[ConfigMap]) { }
      def commit(current: Option[ConfigMap], replacement: Option[ConfigMap]) { f(replacement) }
    })

  def subscribe(subscriber: Subscriber): SubscriptionKey = subscribe(null: String, subscriber)

  override def subscribe(f: (Option[ConfigMap]) => Unit): SubscriptionKey = subscribe(null: String)(f)

  private[configgy] def unsubscribe(subkey: SubscriptionKey) = synchronized {
    subscriberKeys.get(subkey.id) match {
      case None => false
      case Some((node, sub)) =>
        node.subscribers -= sub
        subscriberKeys -= subkey.id
        true
    }
  }

  /**
   * Return a formatted string of all the subscribers, useful for debugging.
   */
  def debugSubscribers() = synchronized {
    "subs=" + subscribers.toString
  }

  /**
   * Un-register this object from JMX. Any existing JMX nodes for this config object will vanish.
   */
  def unregisterWithJmx() = {
    val mbs = ManagementFactory.getPlatformMBeanServer()
    
    jmxNodes foreach (name => mbs unregisterMBean new jmx.ObjectName(name))    
    jmxNodes = Nil
    
    jmxSubscriptionKey foreach unsubscribe
    jmxSubscriptionKey = None
  }

  /**
   * Register this object as a tree of JMX nodes that can be used to view and modify the config.
   * This has the effect of subscribing to the root node, in order to reflect changes to the
   * config object in JMX.
   *
   * @param packageName the name (usually your app's package name) that config objects should
   *     appear inside
   */
  def registerWithJmx(packageName: String): Unit = {
    val mbs = ManagementFactory.getPlatformMBeanServer()
    val nodes = root.getJmxNodes(packageName, "")
    val nodeNames = nodes map (_._1)
    // register any new nodes
    nodes filterNot (jmxNodes contains _) foreach { 
      case (name, bean) =>
        try mbs.registerMBean(bean, new jmx.ObjectName(name))
        catch { case _: jmx.InstanceAlreadyExistsException => ()  } // happens in unit tests.
    }

    // unregister nodes that vanished
    jmxNodes filterNot (nodeNames contains _) foreach { name =>
      mbs unregisterMBean new jmx.ObjectName(name)
    }

    jmxNodes = nodeNames
    jmxPackageName = packageName
    if (jmxSubscriptionKey.isEmpty) {
      jmxSubscriptionKey = Some(subscribe { _ => registerWithJmx(packageName) })
    }
  }


  // -----  modifications that happen within monitored Attributes nodes

  @throws(classOf[ValidationException])
  private def deepChange(name: String, key: String, operation: (ConfigMap, String) => Boolean): Boolean = synchronized {
    // println("deepChange(%s, %s)".format(name, key))
    val fullKey = if (name == "") key else name + "." + key
    val newRoot = root.copy
    
    operation(newRoot, fullKey) && {
      // throws exception if validation fails:
      List(VALIDATE_PHASE, COMMIT_PHASE) foreach { p =>
        subscribers.validate(dotSegments(fullKey), Some(root), Some(newRoot), p)
      }

      if (root.isMonitored) newRoot.setMonitored
      root replaceWith newRoot
      true
    }
  }

  private[configgy] def deepSet(name: String, key: String, value: String) = {
    deepChange(name, key, { (newRoot, fullKey) => newRoot(fullKey) = value; true })
  }

  private[configgy] def deepSet(name: String, key: String, value: Seq[String]) = {
    deepChange(name, key, { (newRoot, fullKey) => newRoot(fullKey) = value; true })
  }

  private[configgy] def deepSet(name: String, key: String, value: ConfigMap) = {
    deepChange(name, key, { (newRoot, fullKey) => newRoot.setConfigMap(fullKey, value); true })
  }

  private[configgy] def deepRemove(name: String, key: String): Boolean = {
    deepChange(name, key, { (newRoot, fullKey) => newRoot.remove(fullKey) })
  }


  // -----  implement AttributeMap by wrapping our root object:

  def getString(key: String): Option[String] = root.getString(key)
  def getConfigMap(key: String): Option[ConfigMap] = root.getConfigMap(key)
  def configMap(key: String): ConfigMap = root.configMap(key)
  def getList(key: String): Seq[String] = root.getList(key)
  def setString(key: String, value: String): Unit = root.setString(key, value)
  def setList(key: String, value: Seq[String]): Unit = root.setList(key, value)
  def setConfigMap(key: String, value: ConfigMap): Unit = root.setConfigMap(key, value)
  def contains(key: String): Boolean = root.contains(key)
  def remove(key: String): Boolean = root.remove(key)
  def keys: Iterator[String] = root.keys
  def asMap(): Map[String, String] = root.asMap()
  def toConfigString = root.toConfigString
  def copy(): ConfigMap = root.copy()
  def inheritFrom = root.inheritFrom
  def inheritFrom_=(config: Option[ConfigMap]) = root.inheritFrom=(config)
}


object Config {
  /**
   * Create a config object from a config file of the given path
   * and filename. The filename must be relative to the path. The path is
   * used to resolve filenames given in "include" lines.
   */
  def fromFile(path: String, filename: String) = returning(new Config) {    
    try _.loadFile(path, filename)
    catch { case e =>
      Logger.get.critical(e, "Failed to load config file '%s/%s'", path, filename)
      throw e
    }
  }

  /**
   * Create a Config object from a config file of the given filename.
   * The base folder will be extracted from the filename and used as a base
   * path for resolving filenames given in "include" lines.
   */
  def fromFile(filename: String): Config = filename.lastIndexOf('/') match {
    case -1   => fromFile(new File(".").getCanonicalPath, filename)
    case idx  => fromFile(filename take idx, filename drop (idx + 1))
  }

  /**
   * Create a Config object from a string containing a config file's contents.
   */
  def fromString(data: String) = returning(new Config)(_ load data)

  /**
   * Create a Config object from the given named resource inside this jar
   * file, using a specific class loader. "include" lines will also operate
   * on resource paths.
   */
  def fromResource(name: String, classLoader: ClassLoader = ClassLoader.getSystemClassLoader): Config =
    returning(new Config) { config =>
      try {
        config.importer = new ResourceImporter(classLoader)
        config.loadFile(name)
      } catch {
        case e: Throwable =>
          Logger.get.critical(e, "Failed to load config resource '%s'", name)
          throw e
      }
    }

  /**
   * Create a Config object from a map of String keys and String values.
   */
  def fromMap(m: Map[String, String]) = 
    returning(new Config) { config => for ((k, v) <- m) config(k) = v }
}
