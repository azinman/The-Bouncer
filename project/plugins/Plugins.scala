class Plugins(info: sbt.ProjectInfo) extends sbt.PluginDefinition(info) {
  val codaRepo = "Coda Hale's Repository" at "http://repo.codahale.com/"
  val protobufSBT = "com.codahale" % "protobuf-sbt" % "0.1.1"
}
