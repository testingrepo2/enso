package org.enso.compiler.test.semantic

import org.enso.compiler.core.Implicits.AsMetadata
import org.enso.compiler.core.ir.{Module, ProcessingPass, Warning}
import org.enso.compiler.core.ir.expression.errors
import org.enso.compiler.core.ir.module.scope.Import
import org.enso.compiler.data.BindingsMap
import org.enso.compiler.phase.ExportsResolution
import org.enso.compiler.pass.analyse.{BindingAnalysis, GatherDiagnostics}
import org.enso.interpreter.runtime
import org.enso.interpreter.runtime.EnsoContext
import org.enso.persist.Persistance
import org.enso.pkg.QualifiedName
import org.enso.common.LanguageInfo
import org.enso.common.MethodNames
import org.enso.compiler.phase.exports.Node
import org.enso.polyglot.RuntimeOptions
import org.graalvm.polyglot.{Context, Engine}
import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.io.ByteArrayOutputStream
import java.nio.file.Paths
import java.util.logging.Level
import java.io.IOException

/** Tests a single package with multiple modules for import/export resolution.
  * Checks whether the exported symbols and defined entities metadata of the modules
  * are correct.
  */
class ImportExportTest
    extends AnyWordSpecLike
    with Matchers
    with BeforeAndAfter {
  private val out = new ByteArrayOutputStream()

  private val engine = Engine
    .newBuilder("enso")
    .allowExperimentalOptions(true)
    .build()

  private val ctx = Context
    .newBuilder(LanguageInfo.ID)
    .engine(engine)
    .allowExperimentalOptions(true)
    .allowAllAccess(true)
    .allowCreateThread(false)
    .out(out)
    .err(out)
    .option(RuntimeOptions.LOG_LEVEL, Level.WARNING.getName())
    .option(RuntimeOptions.DISABLE_IR_CACHES, "true")
    .option(RuntimeOptions.STRICT_ERRORS, "false")
    .logHandler(System.err)
    .option(
      RuntimeOptions.LANGUAGE_HOME_OVERRIDE,
      Paths
        .get("../../test/micro-distribution/component")
        .toFile
        .getAbsolutePath
    )
    .option(RuntimeOptions.EDITION_OVERRIDE, "0.0.0-dev")
    .build()

  private val langCtx: EnsoContext = ctx
    .getBindings(LanguageInfo.ID)
    .invokeMember(MethodNames.TopScope.LEAK_CONTEXT)
    .asHostObject[EnsoContext]()

  private val namespace   = "my_pkg"
  private val packageName = "My_Package"
  private val packageQualifiedName =
    QualifiedName.fromString(namespace + "." + packageName)

  langCtx.getPackageRepository.registerSyntheticPackage(namespace, packageName)

  implicit private class CreateModule(moduleCode: String) {
    def createModule(moduleName: QualifiedName): runtime.Module = {
      val module = new runtime.Module(moduleName, null, moduleCode)
      langCtx.getPackageRepository.registerModuleCreatedInRuntime(
        module.asCompilerModule()
      )
      langCtx.getCompiler.run(module.asCompilerModule())
      module
    }
  }

  implicit private class UnwrapBindingMap(moduleIr: Module) {
    def unwrapBindingMap: BindingsMap = {
      moduleIr.unsafeGetMetadata(BindingAnalysis, "Should be present")
    }
  }

  private def buildExportsGraph(
    modules: List[org.enso.interpreter.runtime.Module]
  ): List[Node] = {
    val compilerCtx       = langCtx.getCompiler.context
    val exportsResolution = new ExportsResolution(compilerCtx)
    val compilerModules   = modules.map(_.asCompilerModule())
    exportsResolution.buildGraph(compilerModules)
  }

  before {
    ctx.enter()
  }

  after {
    ctx.leave()
    out.reset()
  }

  "Import resolution with just two modules" should {
    "resolve one import symbol from a module" in {
      val moduleCode =
        """
          |type Other_Module_Type
          |    Constructor
          |""".stripMargin
      val moduleIr = moduleCode
        .createModule(
          packageQualifiedName.createChild("Other_Module")
        )
        .getIr
      moduleIr.unwrapBindingMap.definedEntities.size shouldEqual 1
      moduleIr.unwrapBindingMap.definedEntities.head.name shouldEqual "Other_Module_Type"
      val otherTypeDefinedEntity =
        moduleIr.unwrapBindingMap.definedEntities.head
          .asInstanceOf[BindingsMap.Type]
      otherTypeDefinedEntity.members.size shouldEqual 1
      otherTypeDefinedEntity.members.head.name shouldEqual "Constructor"

      val mainCode =
        s"""
           |from $namespace.$packageName.Other_Module import Other_Module_Type
           |
           |main = Other_Module_Type.Constructor
           |""".stripMargin
      val mainIr =
        mainCode.createModule(packageQualifiedName.createChild("Main")).getIr
      mainIr.imports.size shouldEqual 1
      mainIr.imports.head match {
        case importErr: errors.ImportExport =>
          fail(
            s"Import should be resolved, but instead produced errors.ImportExport with ${importErr.reason
              .message(null)}"
          )
        case _ => ()
      }
      val mainBindingsMap = mainIr.unwrapBindingMap
      mainBindingsMap.resolvedImports.size shouldEqual 1
      mainBindingsMap
        .resolvedImports(0)
        .target
        .isInstanceOf[BindingsMap.ResolvedModule] shouldBe true
    }

    "resolve two types from a module" in {
      val moduleIr =
        """
          |type Other_Module_Type
          |type Another_Type
          |""".stripMargin
          .createModule(packageQualifiedName.createChild("Other_Module"))
          .getIr
      moduleIr.unwrapBindingMap.definedEntities.size shouldEqual 2
      moduleIr.unwrapBindingMap
        .definedEntities(0)
        .name shouldEqual "Other_Module_Type"
      moduleIr.unwrapBindingMap
        .definedEntities(1)
        .name shouldEqual "Another_Type"

      val mainIr =
        s"""
           |from $namespace.$packageName.Other_Module import Other_Module_Type, Another_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.unwrapBindingMap.resolvedImports.size shouldEqual 1
      mainIr.unwrapBindingMap
        .resolvedImports(0)
        .target
        .isInstanceOf[BindingsMap.ResolvedModule] shouldBe true
    }

    "result in error when importing mixture of existing and non-existing symbols" in {
      """
        |type Existing_Type
        |    Constructor
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("Other_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.Other_Module import Existing_Type, Non_Existing_Symbol
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports.size shouldEqual 1
      mainIr.imports.head.isInstanceOf[errors.ImportExport] shouldBe true
    }

    "result in error when importing a method from type" in {
      """
        |type Other_Type
        |    method self = 42
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("Other_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.Other_Module.Other_Type import method
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports.size shouldEqual 1
      mainIr.imports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .asInstanceOf[
          errors.ImportExport.NoSuchConstructor
        ] shouldEqual errors.ImportExport
        .NoSuchConstructor("Other_Type", "method")
    }

    "result in multiple errors when importing more methods from type" in {
      """
        |type Other_Type
        |    method self = 42
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("Other_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.Other_Module.Other_Type import method, other_method
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports
        .take(2)
        .map(_.asInstanceOf[errors.ImportExport].reason) shouldEqual List(
        errors.ImportExport.NoSuchConstructor("Other_Type", "method"),
        errors.ImportExport.NoSuchConstructor("Other_Type", "other_method")
      )
    }

    "resolve module method" in {
      """
        |module_method =
        |    42
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      val mainIr =
        s"""
           |import $namespace.$packageName.A_Module.module_method
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports.head.isInstanceOf[errors.ImportExport] shouldBe false
      val mainBindingMap = mainIr.unwrapBindingMap
      mainBindingMap.resolvedImports.size shouldEqual 1
      mainBindingMap.resolvedImports.head.target
        .asInstanceOf[BindingsMap.ResolvedModuleMethod]
        .method
        .name shouldEqual "module_method"
    }

    "be able to import constructor from type" in {
      """
        |type My_Type
        |    Constructor
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("My_Module"))

      val mainIr =
        s"""
           |import $namespace.$packageName.My_Module.My_Type.Constructor
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      val bindingMap = mainIr.unwrapBindingMap
      bindingMap.resolvedImports.size shouldBe 1
      bindingMap.resolvedImports.head.target shouldBe a[
        BindingsMap.ResolvedConstructor
      ]

      bindingMap.resolvedImports.head.target
        .asInstanceOf[BindingsMap.ResolvedConstructor]
        .qualifiedName
        .item shouldBe "Constructor"
    }

    "be able to export constructor from type" in {
      """
        |type Boolean
        |    True
        |    False
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("Boolean"))

      val mainIr =
        s"""
           |export $namespace.$packageName.Boolean.Boolean.True
           |export $namespace.$packageName.Boolean.Boolean.False
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      val bindingMap = mainIr.unwrapBindingMap
      bindingMap.exportedSymbols.keys should contain theSameElementsAs List(
        "True",
        "False"
      )
      bindingMap
        .exportedSymbols("True")
        .head
        .isInstanceOf[BindingsMap.ResolvedConstructor] shouldBe true
      bindingMap
        .exportedSymbols("False")
        .head
        .isInstanceOf[BindingsMap.ResolvedConstructor] shouldBe true
    }

    "result in error when trying to import mix of constructors and methods from a type" in {
      """
        |type Other_Module_Type
        |    Constructor_1
        |    Constructor_2 val1 val2
        |    method self = 42
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("Other_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.Other_Module.Other_Module_Type import Constructor_1, method, Constructor_2, non_existing_method
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports
        .take(2)
        .map(_.asInstanceOf[errors.ImportExport].reason) shouldEqual List(
        errors.ImportExport.NoSuchConstructor("Other_Module_Type", "method"),
        errors.ImportExport
          .NoSuchConstructor("Other_Module_Type", "non_existing_method")
      )
    }

    "result in error when trying to import all from a non-type" in {
      """
        |static_method =
        |    42
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("Other_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.Other_Module.static_method import all
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .asInstanceOf[errors.ImportExport.IllegalImportFromMethod]
        .methodName should include("static_method")
    }

    "result in error when trying to import anything from a non-existing symbol" in {
      """
        |# Left blank on purpose
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("Other_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.Other_Module.Non_Existing_Symbol import all
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .asInstanceOf[errors.ImportExport.ModuleDoesNotExist]
        .name should include("Non_Existing_Symbol")
    }

    // TODO[pm]: Fix in https://github.com/enso-org/enso/issues/6669
    "resolve all symbols from transitively exported type" ignore {
      """
        |type A_Type
        |    A_Constructor
        |    a_method self = 1
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      s"""
         |export $namespace.$packageName.A_Module.A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("B_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.B_Module.A_Type import all
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports.size shouldEqual 1
      mainIr.imports.head.isInstanceOf[errors.ImportExport] shouldBe false
      val mainBindingMap = mainIr.unwrapBindingMap
      val resolvedImportTargets =
        mainBindingMap.resolvedImports.map(_.target)
      resolvedImportTargets
        .collect { case c: BindingsMap.ResolvedConstructor => c }
        .map(_.cons.name) should contain theSameElementsAs List("A_Constructor")
    }

    // TODO[pm]: Fix in https://github.com/enso-org/enso/issues/6669
    "resolve constructor from transitively exported type" ignore {
      """
        |type A_Type
        |    A_Constructor
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      s"""
         |import $namespace.$packageName.A_Module.A_Type
         |export $namespace.$packageName.A_Module.A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("B_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.B_Module.A_Type import A_Constructor
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports.size shouldEqual 1
      mainIr.imports.head.isInstanceOf[errors.ImportExport] shouldBe false
      val mainBindingMap = mainIr.unwrapBindingMap
      val resolvedImportTargets =
        mainBindingMap.resolvedImports.map(_.target)
      resolvedImportTargets
        .collect { case c: BindingsMap.ResolvedConstructor => c }
        .map(_.cons.name) should contain theSameElementsAs List("A_Constructor")
    }

    "export is not transitive" in {
      s"""
         |import $namespace.$packageName.A_Module.A_Type
         |export $namespace.$packageName.A_Module.A_Type
         |
         |type A_Type
         |    a_method self = 1
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      s"""
         |import $namespace.$packageName.A_Module.A_Type
         |
         |type B_Type
         |    b_method self = 2
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("B_Module"))
      val mainModule =
        s"""
           |from $namespace.$packageName.B_Module import A_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))

      mainModule.getIr.imports.head
        .isInstanceOf[errors.ImportExport] shouldBe true
      mainModule.getIr.imports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .asInstanceOf[errors.ImportExport.SymbolDoesNotExist]
        .symbolName shouldEqual "A_Type"
    }

    "export type without import should insert synthetic import" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))

      val mainModule = s"""
                          |export $namespace.$packageName.A_Module.A_Type
                          |""".stripMargin
        .createModule(packageQualifiedName.createChild("Main"))

      mainModule.getIr.imports.size shouldBe 1
      mainModule.getIr.imports.head
        .asInstanceOf[Import.Module]
        .isSynthetic shouldBe true

      val resolvedExports = mainModule.getIr.unwrapBindingMap.resolvedExports
      resolvedExports.size shouldBe 1
      resolvedExports.head.target.qualifiedName.item shouldBe "A_Type"
    }

    "(from) export type without import should insert synthetic import" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))

      val mainModule = s"""
                          |from $namespace.$packageName.A_Module export A_Type
                          |""".stripMargin
        .createModule(packageQualifiedName.createChild("Main"))

      mainModule.getIr.imports.size shouldBe 1
      mainModule.getIr.imports.head
        .asInstanceOf[Import.Module]
        .isSynthetic shouldBe true

      val resolvedExports = mainModule.getIr.unwrapBindingMap.resolvedExports
      resolvedExports.size shouldBe 1
    }

    "export module without import should insert synthetic import" in {
      // A_Module is empty on purpose
      """
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))

      val mainModule = s"""
                          |export $namespace.$packageName.A_Module
                          |""".stripMargin
        .createModule(packageQualifiedName.createChild("Main"))

      mainModule.getIr.imports.size shouldBe 1
      mainModule.getIr.imports.head
        .asInstanceOf[Import.Module]
        .isSynthetic shouldBe true

      val resolvedExports = mainModule.getIr.unwrapBindingMap.resolvedExports
      resolvedExports.size shouldBe 1
      resolvedExports.head.target.qualifiedName.item shouldBe "A_Module"
    }

    "export unknown type without import should result in error" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))

      val mainModule = s"""
                          |from $namespace.$packageName.A_Module export UNKNOWN_TYPE_FOO
                          |""".stripMargin
        .createModule(packageQualifiedName.createChild("Main"))

      mainModule.getIr.imports.size shouldBe 1
      mainModule.getIr.imports.head
        .isInstanceOf[errors.ImportExport] shouldBe true
    }
  }

  "Exporting non-existing symbols" should {
    "fail when exporting from current module" in {
      val mainIr =
        s"""
           |
           |from $namespace.$packageName.Main.Main_Type import Main_Constructor
           |from $namespace.$packageName.Main.Main_Type export Main_Constructor, Non_Existing_Ctor
           |
           |type Main_Type
           |  Main_Constructor
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr

      mainIr.exports.size shouldEqual 1
      mainIr.exports.head.isInstanceOf[errors.ImportExport] shouldBe true
      mainIr.exports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .isInstanceOf[errors.ImportExport.SymbolDoesNotExist] shouldBe true
      mainIr.exports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .asInstanceOf[errors.ImportExport.SymbolDoesNotExist]
        .symbolName shouldEqual "Non_Existing_Ctor"
    }

    "fail when exporting from other module" in {
      """
        |# Empty
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))

      val mainIr =
        s"""
           |import $namespace.$packageName.A_Module
           |from $namespace.$packageName.A_Module export baz
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr

      val bindingsMap = mainIr.unwrapBindingMap
      bindingsMap shouldNot be(null)
      mainIr.exports.size shouldEqual 1
      mainIr.exports.head.isInstanceOf[errors.ImportExport] shouldBe true
      mainIr.exports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .isInstanceOf[errors.ImportExport.SymbolDoesNotExist] shouldBe true
      mainIr.exports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .asInstanceOf[errors.ImportExport.SymbolDoesNotExist]
        .symbolName shouldEqual "baz"
    }

    "fail when exporting from type with `from`" in {
      """
        |type A_Type
        |  A_Constructor
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))

      val mainIr =
        s"""
           |import $namespace.$packageName.A_Module.A_Type
           |from $namespace.$packageName.A_Module.A_Type export Non_Existing_Ctor
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr

      mainIr.exports.size shouldEqual 1
      mainIr.exports.head.isInstanceOf[errors.ImportExport] shouldBe true
      mainIr.exports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .isInstanceOf[errors.ImportExport.SymbolDoesNotExist] shouldBe true
      mainIr.exports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .asInstanceOf[errors.ImportExport.SymbolDoesNotExist]
        .symbolName shouldEqual "Non_Existing_Ctor"
    }

    "fail when exporting from type" in {
      """
        |type A_Type
        |  A_Constructor
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))

      val mainIr =
        s"""
           |import $namespace.$packageName.A_Module.A_Type
           |export $namespace.$packageName.A_Module.A_Type.FOO
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr

      mainIr.exports.size shouldEqual 1
      mainIr.exports.head.isInstanceOf[errors.ImportExport] shouldBe true
      mainIr.exports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .isInstanceOf[errors.ImportExport.SymbolDoesNotExist] shouldBe true
      mainIr.exports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .asInstanceOf[errors.ImportExport.SymbolDoesNotExist]
        .symbolName shouldEqual "FOO"
    }

    "fail when exporting from module with `from`" in {
      """
        |foo = 42
        |bar = 23
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))

      val mainIr =
        s"""
           |import $namespace.$packageName.A_Module
           |from $namespace.$packageName.A_Module export foo, bar, baz
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr

      mainIr.exports.size shouldEqual 1
      mainIr.exports.head.isInstanceOf[errors.ImportExport] shouldBe true
      mainIr.exports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .isInstanceOf[errors.ImportExport.SymbolDoesNotExist] shouldBe true
      mainIr.exports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .asInstanceOf[errors.ImportExport.SymbolDoesNotExist]
        .symbolName shouldEqual "baz"
    }
  }

  "Import resolution from another library from micro-distribution honor Main" should {
    "resolve Api from Main" in {
      val mainIr = """
                     |from Test.Logical_Export import Api
                     |
                     |main =
                     |    element = Api.Element.Element.create
                     |    element.describe
                     |""".stripMargin
        .createModule(packageQualifiedName.createChild("Main"))
        .getIr

      mainIr.imports.size shouldEqual 1
      val in = mainIr.imports.head
        .asInstanceOf[Import.Module]

      in.name.name should include("Test.Logical_Export.Main")
      in.onlyNames.get.map(_.name) shouldEqual List("Api")

      val errors = mainIr.preorder.filter(x => x.isInstanceOf[Error])
      errors.size shouldEqual 0
    }

    "not expose Impl from Main" in {
      val mainIr = """
                     |from Test.Logical_Export import Impl
                     |
                     |main = Impl
                     |""".stripMargin
        .createModule(packageQualifiedName.createChild("Main"))
        .getIr

      mainIr.imports.size shouldEqual 1
      mainIr.imports.head.isInstanceOf[errors.ImportExport] shouldBe true
      mainIr.imports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .isInstanceOf[errors.ImportExport.SymbolDoesNotExist] shouldBe true
      mainIr.imports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .asInstanceOf[errors.ImportExport.SymbolDoesNotExist]
        .symbolName shouldEqual "Impl"
    }
  }

  "Ambiguous symbol resolution" should {
    "generate warning when importing same type twice with different import statements" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      val mainIr =
        s"""
           |import $namespace.$packageName.A_Module.A_Type
           |from $namespace.$packageName.A_Module import A_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 2
      val origImport = mainIr.imports(0)
      val warn = mainIr
        .imports(1)
        .diagnostics
        .collect({ case w: Warning.DuplicatedImport => w })
      warn.size shouldEqual 1
      warn.head.originalImport shouldEqual origImport
      warn.head.symbolName shouldEqual "A_Type"
      warn.head.originalImport shouldEqual origImport
    }

    "generate warning when importing same type twice with one-symbol import and all-symbol import" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      val mainIr =
        s"""
           |import $namespace.$packageName.A_Module.A_Type
           |from $namespace.$packageName.A_Module import all
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 2
      val origImport = mainIr.imports(0)
      val warn = mainIr
        .imports(1)
        .diagnostics
        .collect({ case w: Warning.DuplicatedImport => w })
      warn.size shouldEqual 1
      warn.head.originalImport shouldEqual origImport
      warn.head.symbolName shouldEqual "A_Type"
      warn.head.originalImport shouldEqual origImport
    }

    "generate warning when importing same type twice with two all-symbol imports" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.A_Module import all
           |from $namespace.$packageName.A_Module import all
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 2
      val origImport = mainIr.imports(0)
      val warn = mainIr
        .imports(1)
        .diagnostics
        .collect({ case w: Warning.DuplicatedImport => w })
      warn.size shouldEqual 1
      warn.head.originalImport shouldEqual origImport
      warn.head.symbolName shouldEqual "A_Type"
      warn.head.originalImport shouldEqual origImport
    }

    "generate two warnings when importing same type twice with two all-symbol imports" in {
      s"""
         |type A_Type
         |type AA_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.A_Module import all
           |from $namespace.$packageName.A_Module import all
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 2
      val origImport = mainIr.imports(0)
      val warn = mainIr
        .imports(1)
        .diagnostics
        .collect({ case w: Warning.DuplicatedImport => w })
      warn.size shouldEqual 2
      warn.foreach(_.originalImport shouldEqual origImport)
      warn.exists(_.symbolName == "A_Type") shouldEqual true
      warn.exists(_.symbolName == "AA_Type") shouldEqual true
    }

    "work when importing two different types with hiding" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("B_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.A_Module import all hiding A_Type
           |from $namespace.$packageName.B_Module import all
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 2
      mainIr.imports.foreach(
        _.isInstanceOf[errors.ImportExport] shouldEqual false
      )
    }

    "result in error when importing same type twice with two one-symbol import and renamed import" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      s"""
         |type B_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("B_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.A_Module import A_Type
           |import $namespace.$packageName.B_Module.B_Type as A_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 2
      val origImport = mainIr.imports(0)
      val ambiguousImport = mainIr
        .imports(1)
        .asInstanceOf[errors.ImportExport]
        .reason
        .asInstanceOf[errors.ImportExport.AmbiguousImport]
      ambiguousImport.symbolName shouldEqual "A_Type"
      ambiguousImport.originalImport shouldEqual origImport
    }

    "generate warning when importing same type twice in one import statement" in {
      s"""
         |type A_Type
         |static_method = 42
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.A_Module import A_Type, static_method, A_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      val warns = mainIr
        .imports(0)
        .diagnostics
        .collect({ case w: Warning.DuplicatedImport => w })
      warns.size shouldEqual 1
      warns.head.symbolName shouldEqual "A_Type"
    }

    "generate error when importing different type twice with one-symbol import and renamed polyglot import" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.A_Module import A_Type
           |polyglot java import org.enso.example.TestClass as A_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 2
      val origImport = mainIr.imports(0)
      val ambiguousImport = mainIr
        .imports(1)
        .asInstanceOf[errors.ImportExport]
        .reason
        .asInstanceOf[errors.ImportExport.AmbiguousImport]
      ambiguousImport.symbolName shouldEqual "A_Type"
      ambiguousImport.originalImport shouldEqual origImport
    }

    "generate warning when importing same type twice with two renamed polyglot imports" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      val mainIr =
        s"""
           |polyglot java import org.enso.example.TestClass as A_Type
           |polyglot java import org.enso.example.TestClass as A_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 2
      val origImport = mainIr.imports(0)
      val warns = mainIr
        .imports(1)
        .diagnostics
        .collect({ case w: Warning.DuplicatedImport => w })
      warns.size shouldEqual 1
      warns.head.symbolName shouldEqual "A_Type"
      warns.head.originalImport shouldEqual origImport
    }

    "result in error when importing different polyglot types with two renamed polyglot imports" in {
      val mainIr =
        s"""
           |polyglot java import org.enso.example.TestClass as A_Type
           |polyglot java import org.enso.example.TestClass.StaticInnerClass as A_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 2
      val origImport = mainIr.imports(0)
      val ambiguousImport = mainIr
        .imports(1)
        .asInstanceOf[errors.ImportExport]
        .reason
        .asInstanceOf[errors.ImportExport.AmbiguousImport]
      ambiguousImport.symbolName shouldEqual "A_Type"
      ambiguousImport.originalImport shouldEqual origImport
    }

    "generate warnings when importing same type multiple times with one-symbol import statements" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      val mainIr =
        s"""
           |import $namespace.$packageName.A_Module.A_Type
           |import $namespace.$packageName.A_Module.A_Type
           |import $namespace.$packageName.A_Module.A_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 3
      val origImport = mainIr.imports(0)
      val allWarns =
        mainIr.imports.flatMap(_.diagnostics.collect({
          case w: Warning.DuplicatedImport => w
        }))
      allWarns.size shouldEqual 2
      allWarns.foreach(_.symbolName shouldEqual "A_Type")
      allWarns.foreach(_.originalImport shouldEqual origImport)
    }

    "generate warnings when importing same type multiple times with import-all statements" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.A_Module import all
           |from $namespace.$packageName.A_Module import all
           |from $namespace.$packageName.A_Module import all
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 3
      val origImport = mainIr.imports(0)
      val allWarns =
        mainIr.imports.flatMap(_.diagnostics.collect({
          case w: Warning.DuplicatedImport => w
        }))
      allWarns.size shouldEqual 2
      allWarns.foreach(_.symbolName shouldEqual "A_Type")
      allWarns.foreach(_.originalImport shouldEqual origImport)
    }

    "serialize duplicated import warning" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      val mainIr =
        s"""
           |import $namespace.$packageName.A_Module.A_Type
           |from $namespace.$packageName.A_Module import A_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 2
      val warn = mainIr
        .imports(1)
        .diagnostics
        .collect({ case w: Warning.DuplicatedImport => w })
      warn.size shouldEqual 1
      val arr = Persistance.write(
        mainIr,
        {
          case metadata: ProcessingPass.Metadata =>
            metadata.prepareForSerialization(
              langCtx.getCompiler.context.asInstanceOf[metadata.Compiler]
            );
          case obj => obj
        }
      );
      arr should not be empty
    }

    "serialize ambiguous import error" in {
      s"""
         |type A_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))
      s"""
         |type B_Type
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("B_Module"))
      val mainIr =
        s"""
           |from $namespace.$packageName.A_Module import A_Type
           |import $namespace.$packageName.B_Module.B_Type as A_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main_Module"))
          .getIr
      mainIr.imports.size shouldEqual 2
      val ambiguousImport = mainIr
        .imports(1)
        .asInstanceOf[errors.ImportExport]
        .reason
        .asInstanceOf[errors.ImportExport.AmbiguousImport]
      ambiguousImport.symbolName shouldEqual "A_Type"
      try {
        val arr = Persistance.write(
          mainIr,
          {
            case metadata: ProcessingPass.Metadata =>
              metadata.prepareForSerialization(
                langCtx.getCompiler.context.asInstanceOf[metadata.Compiler]
              );
            case obj => obj
          }
        );
        fail("Shouldn't return anything when there is an error" + arr)
      } catch {
        case ex: IOException =>
          ex.getMessage should equal(
            "No persistance for org.enso.compiler.core.ir.expression.errors.ImportExport"
          )
      }
    }
  }

  "Import resolution for three modules" should {

    "XX not resolve symbol that is not explicitly exported" in {
      """
        |type A_Type
        |    A_Constructor
        |    instance_method self = 42
        |
        |static_method =
        |    local_var = 42
        |    local_var
        |
        |# Is not really a variable - it is a method returning a constant, so
        |# it is also considered a static module method
        |glob_var = 42
        |
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))

      val bIr = s"""
                   |from $namespace.$packageName.A_Module import all
                   |from $namespace.$packageName.A_Module export static_method
                   |
                   |type B_Type
                   |    B_Constructor val
                   |""".stripMargin
        .createModule(packageQualifiedName.createChild("B_Module"))
        .getIr
      val bBindingMap = bIr.unwrapBindingMap
      // TODO: bBindingsMap.resolvedExports?
      bBindingMap.exportedSymbols.keys should contain theSameElementsAs List(
        "static_method",
        "B_Type"
      )

      val mainIr =
        s"""
           |from $namespace.$packageName.B_Module import A_Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports.head.isInstanceOf[errors.ImportExport] shouldBe true
      mainIr.imports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .message(null) should include("A_Type")

    }

    "resolve all symbols (types and static module methods) from the module" in {
      s"""
         |type A_Type
         |    A_Constructor
         |    instance_method self = 42
         |
         |static_method =
         |    local_var = 42
         |    local_var
         |
         |# Is not really a variable - it is a method returning a constant, so
         |# it is also considered a static module method
         |glob_var = 42
         |
         |# This is also a static method
         |foreign js js_function = \"\"\"
         |    return 42
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))

      s"""
         |from $namespace.$packageName.A_Module import all
         |from $namespace.$packageName.A_Module export static_method
         |
         |type B_Type
         |    B_Constructor val
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("B_Module"))
        .getIr

      val mainIr =
        s"""
           |from $namespace.$packageName.A_Module import all
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports.size shouldEqual 1
      mainIr.imports.head.isInstanceOf[errors.ImportExport] shouldBe false
    }

    "resolve re-exported symbol" in {
      """
        |type A_Type
        |    A_Constructor
        |    instance_method self = 42
        |
        |static_method =
        |    local_var = 42
        |    local_var
        |
        |# Is not really a variable - it is a method returning a constant, so
        |# it is also considered a static module method
        |glob_var = 42
        |
        |# This is also a static method
        |foreign js js_function = \"\"\"
        |    return 42
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))

      s"""
         |from $namespace.$packageName.A_Module import all
         |from $namespace.$packageName.A_Module export static_method
         |
         |type B_Type
         |    B_Constructor val
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("B_Module"))
        .getIr

      val mainIr =
        s"""
           |from $namespace.$packageName.B_Module import static_method
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports.size shouldEqual 1
      mainIr.imports.head.isInstanceOf[errors.ImportExport] shouldBe false
    }

    "resolve re-exported symbol along with all other symbols" in {
      """
        |type A_Type
        |    A_Constructor
        |    instance_method self = 42
        |
        |static_method =
        |    local_var = 42
        |    local_var
        |
        |# Is not really a variable - it is a method returning a constant, so
        |# it is also considered a static module method
        |glob_var = 42
        |
        |# This is also a static method
        |foreign js js_function = \"\"\"
        |    return 42
        |""".stripMargin
        .createModule(packageQualifiedName.createChild("A_Module"))

      s"""
         |from $namespace.$packageName.A_Module import all
         |from $namespace.$packageName.A_Module export static_method
         |
         |type B_Type
         |    B_Constructor val
         |""".stripMargin
        .createModule(packageQualifiedName.createChild("B_Module"))
        .getIr

      val mainIr =
        s"""
           |from $namespace.$packageName.B_Module import all
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr
      mainIr.imports.size shouldEqual 1
      mainIr.imports.head.isInstanceOf[errors.ImportExport] shouldBe false
    }
  }

  "Private modules" should {
    "not be able to export private module" in {
      """
        |private
        |""".stripMargin
        .createModule(
          packageQualifiedName.createChild("Priv_Module")
        )

      val mainIr = s"""
                      |import $namespace.$packageName.Priv_Module
                      |export $namespace.$packageName.Priv_Module
                      |""".stripMargin
        .createModule(packageQualifiedName.createChild("Main"))
        .getIr

      mainIr.exports.size shouldEqual 1
      mainIr.exports.head.isInstanceOf[errors.ImportExport] shouldBe true
      mainIr.exports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .isInstanceOf[errors.ImportExport.ExportPrivateModule] shouldBe true
    }

    "not be able to export anything from private module itself" in {
      val mainIr =
        s"""
           |private
           |
           |from $namespace.$packageName export Type_In_Priv_Module
           |
           |type Type_In_Priv_Module
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr

      mainIr.exports.size shouldEqual 1
      mainIr.exports.head.isInstanceOf[errors.ImportExport] shouldBe true
      mainIr.exports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .isInstanceOf[
          errors.ImportExport.ExportSymbolsFromPrivateModule
        ] shouldBe true

    }

    "not be able to export anything from private module from Main module" in {
      """
        |private
        |type Type_In_Priv_Module
        |""".stripMargin
        .createModule(
          packageQualifiedName.createChild("Priv_Module")
        )

      val mainIr =
        s"""
           |from $namespace.$packageName.Priv_Module import Type_In_Priv_Module
           |from $namespace.$packageName.Priv_Module export Type_In_Priv_Module
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr

      mainIr.exports.size shouldEqual 1
      mainIr.exports.head.isInstanceOf[errors.ImportExport] shouldBe true
      mainIr.exports.head
        .asInstanceOf[errors.ImportExport]
        .reason
        .isInstanceOf[errors.ImportExport.ExportPrivateModule] shouldBe true
    }

    "be able to import stuff from private modules in the same library" in {
      """
        |private
        |type Type_In_Priv_Module
        |""".stripMargin
        .createModule(
          packageQualifiedName.createChild("Priv_Module")
        )

      val mainIr =
        s"""
           |from $namespace.$packageName.Priv_Module import Type_In_Priv_Module
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr

      val errors = mainIr.preorder.filter(x => x.isInstanceOf[Error])
      errors.size shouldEqual 0
    }
  }

  "Fully qualified names" should {
    "be able to resolve symbols from local project with import" in {
      val modName = packageQualifiedName.createChild("Module")
      """
        |type Type
        |""".stripMargin
        .createModule(modName)

      val mainIr =
        s"""
           |import $namespace.$packageName
           |main = $namespace.$packageName.Type
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("Main"))
          .getIr

      val diags = mainIr
        .unsafeGetMetadata(GatherDiagnostics, "Should be included")
        .diagnostics
      diags.size shouldEqual 0
    }
  }

  "Exports graph building" should {
    def assertAModExportedByBMod(graph: List[Node]): Unit = {
      withClue("There should be only A_Module and B_Module nodes") {
        graph.size shouldBe 2
      }
      val aModNode = graph.find(node =>
        node.module match {
          case BindingsMap.ResolvedModule(modRef) =>
            modRef.getName.item == "A_Module"
          case _ => false
        }
      )
      aModNode shouldBe defined
      val aModNodeExporter =
        aModNode.get.exportedBy.head.exporter
      withClue("A_Module should be exported by B_Module") {
        aModNodeExporter.module
          .isInstanceOf[BindingsMap.ResolvedModule] shouldBe true
        aModNodeExporter.module
          .asInstanceOf[BindingsMap.ResolvedModule]
          .qualifiedName
          .item shouldBe "B_Module"
      }
    }

    // Directly export a single module
    "build exports graph for a module" in {
      val aModule =
        """
          |# Blank on purpose
          |""".stripMargin
          .createModule(packageQualifiedName.createChild("A_Module"))

      val bModule =
        s"""
           |export $namespace.$packageName.A_Module
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("B_Module"))
      val graph = buildExportsGraph(List(aModule, bModule))
      assertAModExportedByBMod(graph)
    }

    "build exports graph for static method" in {
      val aModule =
        """
          |type A_Type
          |    A_Constructor
          |    instance_method self = 42
          |
          |static_method =
          |    local_var = 42
          |    local_var
          |""".stripMargin
          .createModule(packageQualifiedName.createChild("A_Module"))

      val bModule =
        s"""
           |from $namespace.$packageName.A_Module export static_method
           |
           |type B_Type
           |    B_Constructor val
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("B_Module"))
      val graph = buildExportsGraph(List(aModule, bModule))
      assertAModExportedByBMod(graph)
    }

    "build exports graph for type" in {
      val aModule =
        """
          |type A_Type
          |    A_Constructor
          |    instance_method self = 42
          |
          |static_method =
          |    local_var = 42
          |    local_var
          |""".stripMargin
          .createModule(packageQualifiedName.createChild("A_Module"))

      val bModule =
        s"""
           |from $namespace.$packageName.A_Module export A_Type
           |
           |type B_Type
           |    B_Constructor val
           |""".stripMargin
          .createModule(packageQualifiedName.createChild("B_Module"))
      val graph = buildExportsGraph(List(aModule, bModule))
      assertAModExportedByBMod(graph)
    }

    "No exports graph is constructed when only import is used" in {
      val aModule =
        """
          |type A_Type
          |    A_Constructor
          |""".stripMargin
          .createModule(packageQualifiedName.createChild("A_Module"))

      val bModule =
        s"""
          |from $namespace.$packageName.A_Module import A_Type
          |
          |main =
          |    A_Type.A_Constructor
          |""".stripMargin
          .createModule(packageQualifiedName.createChild("B_Module"))
      val graph = buildExportsGraph(List(aModule, bModule))
      withClue("Only two modules are defined") {
        graph.size shouldBe 2
      }
      withClue("There should be no exports") {
        graph.forall(node => node.exports.isEmpty) shouldBe true
      }
    }
  }
}
