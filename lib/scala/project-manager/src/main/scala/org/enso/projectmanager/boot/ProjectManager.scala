package org.enso.projectmanager.boot

import akka.http.scaladsl.Http
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.cli.CommandLine
import org.enso.projectmanager.boot.Globals.{
  ConfigFilename,
  ConfigNamespace,
  FailureExitCode,
  SuccessExitCode
}
import org.enso.projectmanager.boot.command.filesystem.{
  FileSystemCreateDirectoryCommand,
  FileSystemDeleteCommand,
  FileSystemListCommand,
  FileSystemMoveDirectoryCommand,
  FileSystemWritePathCommand
}
import org.enso.projectmanager.boot.command.{CommandHandler, ProjectListCommand}
import org.enso.projectmanager.boot.configuration.{
  MainProcessConfig,
  ProjectManagerConfig
}
import org.enso.projectmanager.protocol.JsonRpcProtocolFactory
import org.enso.version.VersionDescription
import org.slf4j.event.Level
import pureconfig.ConfigSource
import pureconfig.generic.auto._
import zio.Console.{printLine, printLineError, readLine}
import zio.interop.catz.core._
import zio.{ExitCode, Runtime, Scope, UIO, ZAny, ZIO, ZIOAppArgs, ZIOAppDefault}

import java.io.{EOFException, IOException}
import java.nio.file.{FileAlreadyExistsException, Files, Paths}
import java.util.concurrent.ScheduledThreadPoolExecutor

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor}

/** Project manager runner containing the main method.
  */
object ProjectManager extends ZIOAppDefault with LazyLogging {

  /** A configuration of the project manager. */
  lazy val config: ProjectManagerConfig =
    ConfigSource
      .resources(ConfigFilename)
      .withFallback(ConfigSource.systemProperties)
      .at(ConfigNamespace)
      .loadOrThrow[ProjectManagerConfig]

  private lazy val commandHandler = new CommandHandler(
    new JsonRpcProtocolFactory().getProtocol()
  )

  val computeThreadPool = new ScheduledThreadPoolExecutor(
    java.lang.Runtime.getRuntime.availableProcessors()
  )

  val computeExecutionContext: ExecutionContextExecutor =
    ExecutionContext.fromExecutor(
      computeThreadPool,
      th => logger.error("An expected error occurred.", th)
    )

  /** ZIO runtime. */
  implicit override lazy val runtime: Runtime[ZAny] =
    zio.Unsafe.unsafe { implicit unsafe =>
      zio.Runtime.unsafe.fromLayer(
        zio.Runtime.setExecutor(
          zio.Executor.fromExecutionContext(computeExecutionContext)
        )
      )
    }

  /** Main process starting up the server. */
  private def mainProcess(
    processConfig: MainProcessConfig
  ): ZIO[ZAny, IOException, Unit] = {
    val mainModule =
      new MainModule[ZIO[ZAny, +*, +*]](
        config,
        processConfig,
        computeExecutionContext
      )
    for {
      binding <- bindServer(mainModule)
      _       <- logServerStartup()
      _       <- tryReadLine
      _       <- ZIO.succeed { logger.info("Stopping server...") }
      _       <- ZIO.succeed { binding.unbind() }
      _       <- killAllLanguageServer(mainModule)
      _       <- waitTillAllShutdownHooksWillBeFired(mainModule)
      _       <- ZIO.succeed { mainModule.system.terminate() }
    } yield ()
  }

  private def tryReadLine: ZIO[ZAny, Nothing, String] =
    readLine.catchAll { err =>
      ZIO
        .succeed {
          err match {
            case _: EOFException =>
            case _               => logger.warn("Failed to read line.", err)
          }
        }
        .as("")
    }

  private def killAllLanguageServer(mainModule: MainModule[ZIO[ZAny, +*, +*]]) =
    mainModule.languageServerGateway
      .killAllServers()
      .foldZIO(
        failure = th =>
          ZIO.succeed {
            logger.error("An error occurred during killing lang servers.", th)
          },
        success = ZIO.succeed(_)
      )

  private def waitTillAllShutdownHooksWillBeFired(
    mainModule: MainModule[ZIO[ZAny, +*, +*]]
  ) =
    mainModule.languageServerGateway
      .waitTillAllHooksFired()
      .foldZIO(
        failure = th =>
          ZIO.succeed {
            logger
              .error("An error occurred during waiting for shutdown hooks.", th)
          },
        success = ZIO.succeed(_)
      )

  private def runArgs(
    args: Seq[String]
  ): ZIO[Environment with ZIOAppArgs with Scope, Any, Any] = {
    Cli.parse(args.toArray) match {
      case Right(opts) =>
        runOpts(opts).catchAll(th =>
          ZIO.succeed(
            System.err.println(
              s"An error occurred during the program startup: ${th.getMessage}"
            )
          ) *>
          ZIO.succeed(FailureExitCode)
        )
      case Left(error) =>
        (printLine(error) *>
        ZIO.succeed(Cli.printHelp()) *>
        ZIO.succeed(FailureExitCode)).catchAll(th =>
          ZIO.succeed(
            System.err.println(s"Unexpected error: ${th.getMessage}")
          ) *>
          ZIO.succeed(FailureExitCode)
        )
    }
  }

  override def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] =
    getArgs.flatMap(runArgs)

  /** Parses and validates the command line arguments.
    *
    * @param options the command line arguments
    */
  private def parseOpts(
    options: CommandLine
  ): ZIO[ZAny, Throwable, ProjectManagerOptions] = {
    val parseProfilingPath = ZIO
      .attempt {
        Option(options.getOptionValue(Cli.PROFILING_PATH))
          .map(Paths.get(_).toAbsolutePath)
      }
      .flatMap {
        case pathOpt @ Some(path) =>
          ZIO.ifZIO(ZIO.attempt(Files.isDirectory(path)))(
            onTrue = printLineError(
              s"Error: ${Cli.PROFILING_PATH} is a directory: $path"
            ) *>
              ZIO.fail(new FileAlreadyExistsException(path.toString)),
            onFalse = ZIO.succeed(pathOpt)
          )
        case None =>
          ZIO.succeed(None)
      }
      .catchAll { err =>
        printLineError(s"Invalid ${Cli.PROFILING_PATH} argument.") *> ZIO.fail(
          err
        )
      }

    val parseProfilingTime = ZIO
      .attempt {
        Option(options.getOptionValue(Cli.PROFILING_TIME))
          .map(_.toInt.seconds)
      }
      .catchAll { err =>
        printLineError(s"Invalid ${Cli.PROFILING_TIME} argument.") *> ZIO.fail(
          err
        )
      }

    for {
      profilingPath <- parseProfilingPath
      profilingTime <- parseProfilingTime
    } yield ProjectManagerOptions(profilingPath, profilingTime)
  }

  /** The main function of the application, which will be passed the command-line
    * arguments to the program and has to return an `IO` with the errors fully handled.
    */
  private def runOpts(options: CommandLine): ZIO[ZAny, Throwable, ExitCode] = {
    if (options.hasOption(Cli.HELP_OPTION)) {
      ZIO.succeed(Cli.printHelp()) *>
      ZIO.succeed(SuccessExitCode)
    } else if (options.hasOption(Cli.VERSION_OPTION)) {
      displayVersion(options.hasOption(Cli.JSON_OPTION))
    } else if (options.hasOption(Cli.FILESYSTEM_LIST)) {
      val directory = Paths.get(options.getOptionValue(Cli.FILESYSTEM_LIST))
      val fileSystemListCommand =
        FileSystemListCommand[ZIO[ZAny, +*, +*]](config, directory.toFile)
      commandHandler.printJson(fileSystemListCommand.run)
    } else if (options.hasOption(Cli.FILESYSTEM_CREATE_DIRECTORY)) {
      val directory =
        Paths.get(options.getOptionValue(Cli.FILESYSTEM_CREATE_DIRECTORY))
      val fileSystemCreateDirectoryCommand =
        FileSystemCreateDirectoryCommand[ZIO[ZAny, +*, +*]](
          config,
          directory.toFile
        )
      commandHandler.printJson(fileSystemCreateDirectoryCommand.run)
    } else if (options.hasOption(Cli.FILESYSTEM_DELETE)) {
      val directory =
        Paths.get(options.getOptionValue(Cli.FILESYSTEM_DELETE))
      val fileSystemDeleteDirectoryCommand =
        FileSystemDeleteCommand[ZIO[ZAny, +*, +*]](config, directory.toFile)
      commandHandler.printJson(fileSystemDeleteDirectoryCommand.run)
    } else if (options.hasOption(Cli.FILESYSTEM_MOVE_FROM)) {
      val from = Paths.get(options.getOptionValue(Cli.FILESYSTEM_MOVE_FROM))
      val to   = Paths.get(options.getOptionValue(Cli.FILESYSTEM_MOVE_TO))
      val fileSystemMoveDirectoryCommand =
        FileSystemMoveDirectoryCommand[ZIO[ZAny, +*, +*]](
          config,
          from.toFile,
          to.toFile
        )
      commandHandler.printJson(fileSystemMoveDirectoryCommand.run)
    } else if (options.hasOption(Cli.FILESYSTEM_WRITE_PATH)) {
      val path = Paths.get(options.getOptionValue(Cli.FILESYSTEM_WRITE_PATH))
      val fileSystemMoveDirectoryCommand =
        FileSystemWritePathCommand[ZIO[ZAny, +*, +*]](
          config,
          path.toFile
        )
      commandHandler.printJson(fileSystemMoveDirectoryCommand.run)
    } else if (options.hasOption(Cli.PROJECT_LIST)) {
      val projectsPathOpt =
        Option(options.getOptionValue(Cli.PROJECTS_DIRECTORY))
          .map(Paths.get(_).toFile)
      val limitOpt = Option(
        options
          .getParsedOptionValue(Cli.PROJECT_LIST)
          .asInstanceOf[java.lang.Number]
      )
        .map(_.intValue())
      val projectListCommand =
        ProjectListCommand[ZIO[ZAny, +*, +*]](config, projectsPathOpt, limitOpt)
      commandHandler.printJson(projectListCommand.run)
    } else {
      val verbosity  = options.getOptions.count(_ == Cli.option.verbose)
      val logMasking = !options.hasOption(Cli.NO_LOG_MASKING)
      for {
        _        <- displayVersion(false)
        opts     <- parseOpts(options)
        logLevel <- setupLogging(verbosity, logMasking)
        procConf = MainProcessConfig(
          logLevel,
          opts.profilingPath,
          opts.profilingTime
        )
        exitCode <- mainProcess(procConf).fold(
          th => {
            logger.error("Main process execution failed.", th)
            FailureExitCode
          },
          _ => SuccessExitCode
        )
      } yield exitCode
    }
  }

  private def setupLogging(
    verbosityLevel: Int,
    logMasking: Boolean
  ): ZIO[ZAny, IOException, Level] = {
    val level = verbosityLevel match {
      case 0 => Level.INFO
      case 1 => Level.DEBUG
      case _ => Level.TRACE
    }
    ZIO
      .attempt {
        Logging.setup(level, logMasking)
        Logging.waitForSetup()
        ()
      }
      .catchAll { exception =>
        printLineError(s"Failed to setup logger: ${exception.getMessage}")
      }
      .as(level)
  }

  private def displayVersion(
    useJson: Boolean
  ): ZIO[ZAny, IOException, ExitCode] = {
    printLine(makeVersionDescription.asString(useJson)) *>
    ZIO.succeed(SuccessExitCode)
  }

  private def makeVersionDescription: VersionDescription =
    VersionDescription.make(
      "Enso Project Manager",
      includeRuntimeJVMInfo         = false,
      enableNativeImageOSWorkaround = true
    )

  private def logServerStartup(): UIO[Unit] =
    ZIO.succeed {
      logger.info(
        "Started server at {}:{}, press enter to kill server",
        config.server.host,
        config.server.port
      )
    }

  private def bindServer(
    module: MainModule[ZIO[ZAny, +*, +*]]
  ): UIO[Http.ServerBinding] =
    ZIO.succeed {
      Await.result(
        module.server.bind(config.server.host, config.server.port),
        3.seconds
      )
    }
}
