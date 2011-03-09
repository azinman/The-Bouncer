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

import java.util.regex.Pattern
import javax.{management => jmx}
import scala.collection.{immutable, mutable, Map}
import scala.util.Sorting
import net.lag.extensions._

private[configgy] object Attributes {
  abstract class CellType[T](val value: T) {    
    def fromConfig: String => T
    def toConfig: T => String
    def isValid: String => Boolean
  }

  sealed abstract class Cell { def asString: String } 
  case class StringCell(value: String) extends Cell { def asString = value }
  case class AttributesCell(attr: Attributes) extends Cell { def asString = attr.toString }
  case class StringListCell(array: Array[String]) extends Cell { def asString = array.mkString("[", ",", "]") }
}
import Attributes._

/**
 * Actual implementation of ConfigMap.
 * Stores items in Cell objects, and handles interpolation and key recursion.
 */
private[configgy] class Attributes(val config: Config, val name: String) extends ConfigMap {
  private val cells = new mutable.HashMap[String, Cell]
  private var monitored = false
  var inheritFrom: Option[ConfigMap] = None

  def keys: Iterator[String] = cells.keysIterator

  def isCompound(key: String) = key contains "."
  
  def inheritedFn[T](pf: PartialFunction[ConfigMap, T], default: T): T =
    if (inheritFrom.isDefined && pf.isDefinedAt(inheritFrom.get)) pf(inheritFrom.get)
    else default

  override def toString() =
    """{%s%s: %s}""".format(name,
      inheritedFn({ case a: Attributes => " (inherit=" + a.name + ")" }, ""),
      (sortedKeys map (key => key + "=" + (cells(key) match {
        case StringCell(x) => "\"" + x.quoteC + "\""
        case AttributesCell(x) => x.toString
        case StringListCell(x) => x.mkString("[", ",", "]")
      }) + " ")).mkString
    )

  override def equals(obj: Any) = obj match {
    case other: Attributes  => cells == other.cells
    case _                  => false
  }

  /**
   * Look up a value cell for a given key. If the key is compound (ie,
   * "abc.xyz"), look up the first segment, and if it refers to an inner
   * Attributes object, recursively look up that cell. If it's not an
   * Attributes or it doesn't exist, return None. For a non-compound key,
   * return the cell if it exists, or None if it doesn't.
   */
  private def lookupCell(key: String): Option[Cell] = {
    def parentLookup(k: String) = inheritedFn({ case a: Attributes => a lookupCell k }, None)
    key.split("\\.", 2) match {
      case Array(first)       =>
        (cells get first) orElse parentLookup(first)
        
      case Array(first, rest) =>
        (cells get first) match {
          case Some(AttributesCell(x))  => x lookupCell rest
          case None                     => parentLookup(key)
          case _                        => None
        }
    }
  }

  /**
   * Determine if a key is compound (and requires recursion), and if so,
   * return the nested Attributes block and simple key that can be used to
   * make a recursive call. If the key is simple, return None.
   *
   * If the key is compound, but nested Attributes objects don't exist
   * that match the key, an attempt will be made to create the nested
   * Attributes objects. If one of the key segments already refers to an
   * attribute that isn't a nested Attribute object, a ConfigException
   * will be thrown.
   *
   * For example, for the key "a.b.c", the Attributes object for "a.b"
   * and the key "c" will be returned, creating the "a.b" Attributes object
   * if necessary. If "a" or "a.b" exists but isn't a nested Attributes
   * object, then an ConfigException will be thrown.
   */
  @throws(classOf[ConfigException])
  private def recurse(key: String): Option[(Attributes, String)] =      
    if (isCompound(key)) {
      val Array(first, rest) = key.split("\\.", 2)
      val attr = (cells get first) match {
        case Some(AttributesCell(x))  => x
        case Some(_)                  => throw new ConfigException("Illegal key " + key)
        case None                     => createNested(first)
      }
      attr.recurse(rest) orElse Some((attr, rest))
    }
    else None

  def replaceWith(newAttributes: Attributes): Unit = {
    // stash away subnodes and reinsert them.
    val subnodes = for ((key, cell @ AttributesCell(_)) <- cells.toList) yield (key, cell)

    cells.clear
    cells ++= newAttributes.cells
    for ((key: String, cell) <- subnodes) {
      (newAttributes.cells get key) match {
        case Some(AttributesCell(newattr)) =>
          cell.attr.replaceWith(newattr)
          cells(key) = cell
        case None =>
          cell.attr.replaceWith(new Attributes(config, ""))
      }
    }
  }

  private def createNested(key: String): Attributes = {
    val newKey = if (name == "") key else name + "." + key
    
    val attr = new Attributes(config, newKey)
    if (monitored)
      attr.setMonitored

    cells(key) = new AttributesCell(attr)
    attr
  }

  def getString(key: String): Option[String] = {
    lookupCell(key) match {
      case Some(StringCell(x)) => Some(x)
      case Some(StringListCell(x)) => Some(x.mkString("[", ",", "]"))
      case _ => None
    }
  }

  def getConfigMap(key: String): Option[ConfigMap] = {
    lookupCell(key) match {
      case Some(AttributesCell(x)) => Some(x)
      case _ => None
    }
  }

  def configMap(key: String): ConfigMap = makeAttributes(key, true)

  private[configgy] def makeAttributes(key: String): Attributes = makeAttributes(key, false)

  private[configgy] def makeAttributes(key: String, withInherit: Boolean): Attributes =
    if (key == "") this
    else recurse(key) match {
      case Some((attr, name)) => attr.makeAttributes(name, withInherit)
      case None => 
        val cell = if (withInherit) lookupCell(key) else cells.get(key)
        cell match {
          case Some(AttributesCell(x)) => x
          case Some(_) => throw new ConfigException("Illegal key " + key)
          case None => createNested(key)
        }
    }

  def getList(key: String): Seq[String] = {
    lookupCell(key) match {
      case Some(StringListCell(x))  => x
      case Some(StringCell(x))      => List(x)
      case _                        => Nil
    }
  }

  def setString(key: String, value: String): Unit =
    if (monitored) config.deepSet(name, key, value)
    else recurse(key) match {
      case Some((attr, name)) => attr.setString(name, value)
      case None => (cells get key) match {
        case Some(AttributesCell(_)) => throw new ConfigException("Illegal key " + key)
        case _ => cells.put(key, new StringCell(value))
      }
    }

  def setList(key: String, value: Seq[String]): Unit =
    if (monitored) config.deepSet(name, key, value)
    else recurse(key) match {
      case Some((attr, name)) => attr.setList(name, value)
      case None => (cells get key) match {
        case Some(AttributesCell(_)) => throw new ConfigException("Illegal key " + key)
        case _ => cells.put(key, new StringListCell(value.toArray))
      }
    }

  def setConfigMap(key: String, value: ConfigMap): Unit = {
    def attrCopy = value.copy.asInstanceOf[Attributes]
    
    if (monitored) config.deepSet(name, key, value)
    else recurse(key) match {
      case Some((attr, name)) => attr.setConfigMap(name, value)
      case None =>
        (cells get key) match {
          case Some(AttributesCell(_)) | None =>
            cells.put(key, new AttributesCell(attrCopy))
          case _ =>
            throw new ConfigException("Illegal key " + key)
        }
    }
  }

  def contains(key: String): Boolean =
    recurse(key) match {
      case Some((attr, name)) => attr.contains(name)
      case None => cells.contains(key)
    }

  def remove(key: String): Boolean =
    if (monitored) config.deepRemove(name, key)
    else recurse(key) match {
      case Some((attr, name)) => attr.remove(name)
      case None => (cells remove key).isDefined
    }

  def asMap: Map[String, String] =
    cells.foldLeft(immutable.Map.empty[String, String]) {
      case (ret, (key, value))  => 
        value match {
          case StringCell(x)      => ret.updated(key, x)
          case StringListCell(x)  => ret.updated(key, x.mkString("[", ",", "]"))
          case AttributesCell(x)  => x.asMap.foldLeft(ret) { case (m, (k, v)) => m.updated(key + "." + k, v) }
        }
    }

  def toConfigString: String = {
    toConfigList().mkString("", "\n", "\n")
  }

  private def toConfigList(): List[String] =
    cells.toList sortBy (_._1) flatMap {
      case (key, StringCell(x))     =>
        List("""%s = "%s"""".format(key, x.quoteC))
      
      case (key, StringListCell(x))  => 
        val xs = x.toList map ("""  "%s",""" format _.quoteC)
        List(key + " = [") ::: xs ::: List("]")
        
      case (key, AttributesCell(node))  =>
        val xs = node.toConfigList() map ("  " + _)
        val front = key + node.inheritedFn({ case x: Attributes => " (inherit=\"" + x.name + "\")" }, "") + " {"
        List(front) ::: xs ::: List("}")
    }

  def subscribe(subscriber: Subscriber) = config.subscribe(name, subscriber)

  // substitute "$(...)" strings with looked-up vars
  // (and find "\$" and replace them with "$")
  private val INTERPOLATE_RE = """(?<!\\)\$\((\w[\w\d\._-]*)\)|\\\$""".r

  protected[configgy] def interpolate(root: Attributes, s: String): String = {
    def lookup(key: String, path: List[ConfigMap]): String = path match {
      case Nil        => ""
      case attr :: xs => (attr getString key) getOrElse lookup(key, xs)
    }

    s.regexSub(INTERPOLATE_RE) { m =>
      if (m.matched == "\\$") "$"
      else lookup(m.group(1), List(this, root, EnvironmentAttributes))
    }
  }

  protected[configgy] def interpolate(key: String, s: String): String =
    recurse(key) match {
      case Some((attr, _))  => attr.interpolate(this, s)
      case None             => interpolate(this, s)
    }

  /* set this node as part of a monitored config tree. once this is set,
   * all modification requests go through the root Config, so validation
   * will happen.
   */
  protected[configgy] def setMonitored: Unit = 
    if (!monitored) {
      monitored = true
      cells.valuesIterator collect { case AttributesCell(x) => x.setMonitored }
    }

  protected[configgy] def isMonitored = monitored

  // make a deep copy of the Attributes tree.
  def copy(): Attributes = copyTo(new Attributes(config, name))

  private def copyTo(attr: Attributes): Attributes = {
    inheritedFn({ case a: Attributes => a copyTo attr }, ())
    
    for ((key, value) <- cells) value match {
      case StringCell(x)      => attr(key) = x
      case StringListCell(x)  => attr(key) = x
      case AttributesCell(x)  => attr.cells += (key -> new AttributesCell(x.copy()))
    }    
    attr
  }

  private def mkJMXMBean(key: String, clazz: String) = new jmx.MBeanAttributeInfo(key, clazz, "", true, true, false)
  def asJmxAttributes(): Array[jmx.MBeanAttributeInfo] = cells collect { 
    case (key: String, StringCell(_))     => mkJMXMBean(key, "java.lang.String")
    case (key: String, StringListCell(_)) => mkJMXMBean(key, "java.util.List")
  } toArray

  def asJmxDisplay(key: String): AnyRef = cells.get(key) match {
    case Some(StringCell(x)) => x
    case Some(StringListCell(x)) => java.util.Arrays.asList(x: _*)
    case x => null
  }

  def getJmxNodes(prefix: String, name: String): List[(String, JmxWrapper)] = {
    def nameEmpty(yes: String, no: String) = if (name == "") yes else no
    
    val hd = "%s:type=Config,name=%s".format(prefix, nameEmpty("(root)", name))
    val tl = cells.toList collect { 
      case (key: String, AttributesCell(x)) =>
        x.getJmxNodes(prefix, nameEmpty(key, name + "." + key))
    } flatten
    
    (hd, new JmxWrapper(this)) :: tl
  }
}
