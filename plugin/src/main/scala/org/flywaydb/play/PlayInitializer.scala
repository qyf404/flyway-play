/*
 * Copyright 2013 Toshiyuki Takahashi
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
package org.flywaydb.play

import java.io.FileNotFoundException
import javax.inject._

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationInfo
import org.flywaydb.core.internal.util.jdbc.DriverDataSource
import play.api._
import play.core._

import scala.collection.JavaConverters._

@Singleton
class PlayInitializer @Inject() (
  configuration: Configuration,
  environment: Environment,
  webCommands: WebCommands) {

  private val flywayConfigurations = {
    val configReader = new ConfigReader(configuration, environment)
    configReader.getFlywayConfigurations
  }

  private val allDatabaseNames = flywayConfigurations.keys

  private val flywayPrefixToMigrationScript = "db/migration"

  private def migrationFileDirectoryExists(path: String): Boolean = {
    environment.resource(path) match {
      case Some(_) =>
        Logger.debug(s"Directory for migration files found. $path")
        true

      case None =>
        Logger.warn(s"Directory for migration files not found. $path")
        false

    }
  }

  private lazy val flyways: Map[String, Flyway] = {
    for {
      (dbName, configuration) <- flywayConfigurations
      migrationFilesLocation = s"$flywayPrefixToMigrationScript/$dbName"
      if migrationFileDirectoryExists(migrationFilesLocation)
    } yield {
      val flyway = new Flyway
      val database = configuration.database
      val dataSource = new DriverDataSource(
        getClass.getClassLoader,
        database.driver,
        database.url,
        database.user,
        database.password,
        null)
      flyway.setDataSource(dataSource)
      if (configuration.locations.nonEmpty) {
        val locations = configuration.locations.map(location => s"$migrationFilesLocation/$location")
        flyway.setLocations(locations: _*)
      } else {
        flyway.setLocations(migrationFilesLocation)
      }
      configuration.encoding.foreach(flyway.setEncoding)
      flyway.setSchemas(configuration.schemas: _*)
      configuration.table.foreach(flyway.setTable)
      configuration.placeholderReplacement.foreach(flyway.setPlaceholderReplacement)
      flyway.setPlaceholders(configuration.placeholders.asJava)
      configuration.placeholderPrefix.foreach(flyway.setPlaceholderPrefix)
      configuration.placeholderSuffix.foreach(flyway.setPlaceholderSuffix)
      configuration.sqlMigrationPrefix.foreach(flyway.setSqlMigrationPrefix)
      configuration.repeatableSqlMigrationPrefix.foreach(flyway.setRepeatableSqlMigrationPrefix)
      configuration.sqlMigrationSeparator.foreach(flyway.setSqlMigrationSeparator)
      setSqlMigrationSuffixes(configuration, flyway)
      configuration.ignoreFutureMigrations.foreach(flyway.setIgnoreFutureMigrations)
      configuration.validateOnMigrate.foreach(flyway.setValidateOnMigrate)
      configuration.cleanOnValidationError.foreach(flyway.setCleanOnValidationError)
      configuration.cleanDisabled.foreach(flyway.setCleanDisabled)
      configuration.initOnMigrate.foreach(flyway.setBaselineOnMigrate)
      configuration.outOfOrder.foreach(flyway.setOutOfOrder)

      dbName -> flyway
    }
  }

  private def setSqlMigrationSuffixes(configuration: FlywayConfiguration, flyway: Flyway): Unit = {
    configuration.sqlMigrationSuffix.foreach(_ =>
      Logger.warn("sqlMigrationSuffix is deprecated in Flyway 5.0, and will be removed in a future version. Use sqlMigrationSuffixes instead."))
    val suffixes: Seq[String] = configuration.sqlMigrationSuffixes ++ configuration.sqlMigrationSuffix
    if (suffixes.nonEmpty) flyway.setSqlMigrationSuffixes(suffixes: _*)
  }

  private def migrationDescriptionToShow(dbName: String, migration: MigrationInfo): String = {
    val locations = flywayConfigurations(dbName).locations
    (if (locations.nonEmpty) locations.map(location => environment.resourceAsStream(s"$flywayPrefixToMigrationScript/$dbName/$location/${migration.getScript}"))
      .find(resource => resource.nonEmpty).flatten
    else {
      environment.resourceAsStream(s"$flywayPrefixToMigrationScript/$dbName/${migration.getScript}")
    }).map { in =>
      s"""|--- ${migration.getScript} ---
          |${FileUtils.readInputStreamToString(in)}""".stripMargin
    }.orElse {
      import scala.util.control.Exception._
      val code = for {
        script <- FileUtils.findJdbcMigrationFile(environment.rootPath, migration.getScript)
      } yield FileUtils.readFileToString(script)
      allCatch opt { environment.classLoader.loadClass(migration.getScript) } map { _ =>
        s"""|--- ${migration.getScript} ---
            |$code""".stripMargin
      }
    }.getOrElse(throw new FileNotFoundException(s"Migration file not found. ${migration.getScript}"))
  }

  private def checkState(dbName: String): Unit = {
    flyways.get(dbName).foreach { flyway =>
      val pendingMigrations = flyway.info().pending
      if (pendingMigrations.nonEmpty) {
        throw InvalidDatabaseRevision(
          dbName,
          pendingMigrations.map(migration => migrationDescriptionToShow(dbName, migration)).mkString("\n"))
      }

      if (flywayConfigurations(dbName).validateOnStart) {
        flyway.validate()
      }
    }
  }

  def onStart(): Unit = {
    val flywayWebCommand = new FlywayWebCommand(configuration, environment, flywayPrefixToMigrationScript, flyways)
    webCommands.addHandler(flywayWebCommand)

    for (dbName <- allDatabaseNames) {
      if (environment.mode == Mode.Test || flywayConfigurations(dbName).auto) {
        migrateAutomatically(dbName)
      } else {
        checkState(dbName)
      }
    }
  }

  private def migrateAutomatically(dbName: String): Unit = {
    flyways.get(dbName).foreach { flyway =>
      flyway.migrate()
    }
  }

  val enabled: Boolean =
    !configuration.getOptional[String]("flywayplugin").contains("disabled")

  if (enabled) {
    onStart()
  }

}
