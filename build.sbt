/*
 * Copyright 2021 Scala.js (https://www.scala-js.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.{ChromeDriver, ChromeOptions}
import org.openqa.selenium.firefox.{FirefoxOptions, FirefoxProfile}
import org.openqa.selenium.remote.server.{DriverFactory, DriverProvider}

import org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv
import org.scalajs.jsenv.selenium.SeleniumJSEnv

import java.util.concurrent.TimeUnit

ThisBuild / tlBaseVersion := "1.1"

ThisBuild / organization := "org.scala-js"
ThisBuild / organizationName := "Scala.js (https://www.scala-js.org/)"
ThisBuild / startYear := Some(2021)
ThisBuild / developers := List(
  Developer("djspiewak", "Daniel Spiewak", "@djspiewak", url("https://github.com/djspiewak")),
  Developer("armanbilge", "Arman Bilge", "@armanbilge", url("https://github.com/armanbilge")))

ThisBuild / homepage := Some(url("https://github.com/scala-js/scala-js-macrotask-executor"))

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/scala-js/scala-js-macrotask-executor"),
    "git@github.com:scala-js/scala-js-macrotask-executor.git"))

// build and matrix configuration

ThisBuild / crossScalaVersions := Seq("2.11.12", "2.12.15", "2.13.7", "3.1.3")

ThisBuild / githubWorkflowBuildPreamble ++= Seq(
  WorkflowStep.Use(
    UseRef.Public("actions", "setup-node", "v3"),
    name = Some("Setup NodeJS v16 LTS"),
    params = Map("node-version" -> "16", "cache" -> "npm"),
    cond = Some("matrix.ci == 'ciNode' || matrix.ci == 'ciJSDOMNodeJS'"),
  ),
  WorkflowStep.Run(
    List("npm install"),
    name = Some("Install jsdom"),
    cond = Some("matrix.ci == 'ciJSDOMNodeJS'"),
  ),
)

val ciVariants = List("ciNode", "ciFirefox", "ciChrome", "ciJSDOMNodeJS")

ThisBuild / githubWorkflowBuildMatrixAdditions += "ci" -> ciVariants

ThisBuild / githubWorkflowBuild := Seq(WorkflowStep.Sbt(List("${{ matrix.ci }}")))

addCommandAlias("ci", ciVariants.mkString("; ", "; ", ""))

addCommandAlias("ciNode", "; set Global / useJSEnv := JSEnv.NodeJS; test; core/doc; core/mimaReportBinaryIssues")
addCommandAlias("ciFirefox", "; set Global / useJSEnv := JSEnv.Firefox; test; set Global / useJSEnv := JSEnv.NodeJS")
addCommandAlias("ciChrome", "; set Global / useJSEnv := JSEnv.Chrome; test; set Global / useJSEnv := JSEnv.NodeJS")
addCommandAlias("ciJSDOMNodeJS", "; set Global / useJSEnv := JSEnv.JSDOMNodeJS; test; set Global / useJSEnv := JSEnv.NodeJS")

// release configuration

ThisBuild / githubWorkflowArtifactUpload := false

// environments

lazy val useJSEnv =
  settingKey[JSEnv]("Use Node.js or a headless browser for running Scala.js tests")

Global / useJSEnv := JSEnv.NodeJS

ThisBuild / Test / jsEnv := {
  import JSEnv._

  val old = (Test / jsEnv).value

  useJSEnv.value match {
    case NodeJS => old
    case JSDOMNodeJS => new JSDOMNodeJSEnv()
    case Firefox =>
      val profile = new FirefoxProfile()
      profile.setPreference("privacy.file_unique_origin", false)
      val options = new FirefoxOptions()
      options.setProfile(profile)
      options.setHeadless(true)
      new SeleniumJSEnv(options)
    case Chrome =>
      val options = new ChromeOptions()
      options.setHeadless(true)
      options.addArguments("--allow-file-access-from-files")
      val factory = new DriverFactory {
        val defaultFactory = SeleniumJSEnv.Config().driverFactory
        def newInstance(capabilities: org.openqa.selenium.Capabilities): WebDriver = {
          val driver = defaultFactory.newInstance(capabilities).asInstanceOf[ChromeDriver]
          driver.manage().timeouts().pageLoadTimeout(1, TimeUnit.HOURS)
          driver.manage().timeouts().setScriptTimeout(1, TimeUnit.HOURS)
          driver
        }
        def registerDriverProvider(provider: DriverProvider): Unit =
          defaultFactory.registerDriverProvider(provider)
      }
      new SeleniumJSEnv(options, SeleniumJSEnv.Config().withDriverFactory(factory))
  }
}

ThisBuild / testOptions += Tests.Argument(TestFrameworks.JUnit, "-a", "-s", "-v")

// project structure

lazy val root = project
  .aggregate(core, webworker)
  .enablePlugins(NoPublishPlugin)

lazy val core = project
  .in(file("core"))
  .settings(
    name := "scala-js-macrotask-executor",
  )
  .enablePlugins(ScalaJSPlugin, ScalaJSJUnitPlugin)

// this project solely exists for testing purposes
lazy val webworker = project
  .in(file("webworker"))
  .dependsOn(core % "compile->test")
  .settings(
    name := "scala-js-macrotask-executor-webworker",
    scalaJSUseMainModuleInitializer := true,
    (Test / test) := {
      if (useJSEnv.value.isBrowser)
        (Test / test).dependsOn(Compile / fastOptJS).value
      else
        ()
    },
    buildInfoKeys := Seq(
      BuildInfoKey(
        "workerDir" -> {
          val outputDir = (Compile / fastLinkJS / scalaJSLinkerOutputDirectory).value
          outputDir.getAbsolutePath()
        }
      )
    ),
    buildInfoPackage := "org.scalajs.macrotaskexecutor",
  )
  .enablePlugins(ScalaJSPlugin, ScalaJSJUnitPlugin, BuildInfoPlugin, NoPublishPlugin)
