import sbt._
import Process._
import java.util.jar.Attributes
import java.text.SimpleDateFormat
import java.io.{BufferedReader, FileReader, FileWriter, File}
import java.util.{Date, Properties}
import FileUtilities._

import java.io.File
import java.util.jar.{Attributes, Manifest}
import scala.collection.mutable.ListBuffer
import scala.collection.mutable
import scala.runtime.RichString

class TheBouncerBuild(info: ProjectInfo) extends DefaultProject(info) with protobuf.ProtobufCompiler with AutoCompilerPlugins {
  val protobuf = "com.google.protobuf" % "protobuf-java" % "2.3.0" withSources()

  // override me for releases!
  override def mainClass = Some("com.netcapital.thebouncer.Server")

  // Make a build.properties file and sneak it into the packaged jar. From Twitter's code
  def packageResourcesPath = mainResourcesOutputPath
  def buildPropertiesPath = packageResourcesPath / "build.properties"
  override def packagePaths = super.packagePaths +++ buildPropertiesPath

  override def fork = {
    val config = forkRun(
      "-server" ::
      Nil)
    config
  }

  // build the executable jar's classpath.
  // (why is it necessary to explicitly remove the target/{classes,resources} paths? hm.)
  def dependentJars =
      publicClasspath +++ mainDependencies.scalaJars --- mainCompilePath --- mainResourcesOutputPath
  def dependentJarNames = dependentJars.getFiles.map(_.getName).filter(_.endsWith(".jar"))
  override def manifestClassPath = Some(dependentJarNames.map { "libs/" + _ }.mkString(" "))
}
