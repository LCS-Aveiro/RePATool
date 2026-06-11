import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}
import sbtassembly.MergeStrategy

val javaFXModules = Seq("base", "controls", "fxml", "graphics", "media", "swing", "web")
val osName = System.getProperty("os.name") match {
  case n if n.startsWith("Linux")   => "linux"
  case n if n.startsWith("Mac")     => "mac"
  case n if n.startsWith("Windows") => "win"
  case _                            => throw new Exception("Unknown OS")
}

val scala3Version = "3.3.1"

lazy val repa = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full) 
  .in(file("."))
  .settings(
    name := "repa",
    version := "0.1.0",
    scalaVersion := scala3Version,
    scalacOptions ++= Seq("-feature"),
    
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %%% "scala-xml" % "2.2.0",
      "org.scalameta" %%% "munit" % "0.7.29" % Test
    )
  )
  .jsSettings(
    scalaJSUseMainModuleInitializer := false,
    
    Compile / fastLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / ".." / "docs" / "js" / "gen",
    Compile / fullLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / ".." / "docs" / "js" / "gen"
  )
  .jvmSettings(
    
   
    Compile / unmanagedResourceDirectories += baseDirectory.value.getParentFile / "docs",
    assembly / assemblyJarName := "RePATool.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case x => MergeStrategy.first
    }
  )

lazy val repaJS = repa.js
lazy val repaJVM = repa.jvm