package io.github.jbellis.brokk.analyzer.builder

import io.github.jbellis.brokk.analyzer.builder.CpgTestFixture.*
import io.github.jbellis.brokk.analyzer.implicits.PathExt.*
import io.github.jbellis.brokk.analyzer.implicits.X2CpgConfigExt.*
import io.joern.x2cpg.X2CpgConfig
import io.shiftleft.codepropertygraph.generated.nodes.AstNode
import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes}
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.types.structure.FileTraversal

import java.nio.file.Files
import scala.util.{Failure, Success, Using}

trait IncrementalBuildTestFixture[R <: X2CpgConfig[R]] {
  this: CpgTestFixture[R] =>

  /**
   * Tests the incremental construction of a project via two changes. Each change must have configurations pointing to
   * different directories to avoid collisions.
   */
  def testIncremental(beforeChange: MockProject[R], afterChange: MockProject[R])(using builder: CpgBuilder[R]): Unit = {
    withClue("The 'beforeChange' project must point to a different directory to the 'afterChange' project") {
      beforeChange.config.inputPath should not be afterChange.config.inputPath
    }
    val beforeConfig = beforeChange.config
    Using.resource(beforeChange.buildAndOpen) // Build and close initial CPG, serializing it at `config.outputPath`
    afterChange.copy(config = beforeConfig).writeFiles // place new files at the "old" path
    Using.Manager { use =>
      // Old path now has new files, so re-build this for updates
      val updatedCpg = beforeConfig.build match {
        case Failure(e) => fail("Exception occurred while incrementally updating CPG.", e)
        case Success(config) => use(config.open)
      }
      val fromScratchCpg = use(afterChange.buildAndOpen)
      verifyConsistency(updatedCpg, fromScratchCpg)
    }.failed.foreach(e => throw e) // failures are exceptions, thus must be propagated
  }

  protected def withIncrementalTestConfig(f: (R, R) => Unit)(implicit initialConfig: () => R = () => defaultConfig): Unit = {
    val tempDirA = Files.createTempDirectory("brokk-incremental-cpg-A-test-")
    val tempDirB = Files.createTempDirectory("brokk-incremental-cpg-B-test-")
    try {
      val newConfigA = setConfigPaths(tempDirA, initialConfig())
      val newConfigB = setConfigPaths(tempDirB, initialConfig())
      f(newConfigA, newConfigB)
    } finally {
      tempDirA.deleteRecursively(suppressExceptions = true)
      tempDirB.deleteRecursively(suppressExceptions = true)
    }
  }

  /**
   * Verifies/asserts that the 'updated' CPG is equivalent to the 'fromScratch' CPG and has no other oddities.
   *
   * @param updated     the incrementally updated CPG.
   * @param fromScratch the CPG built from scratch, i.e., not incrementally.
   */
  private def verifyConsistency(updated: Cpg, fromScratch: Cpg): Unit = {
    /**
     * Asserts that, in the updated CPG, there is at most 1 edge of the given kind between two nodes.
     *
     * @param edgeKind the edge kind to verify.
     */
    def assertSingleEdgePairs(edgeKind: String): Unit = {
      withClue(s"Detected more than one ${edgeKind} edge between the same two nodes.") {
        updated.graph.allEdges
          .collect { case e if e.edgeKind == updated.graph.schema.getEdgeKindByLabel(edgeKind) => e }
          .groupCount { e => (e.src.id(), e.dst.id()) }
          .values.count(_ > 1) shouldBe 0
      }
    }

    // Assert only one meta data node exists
    withClue("The number of meta data nodes is not 1.") {
      updated.metaData.size shouldBe 1
    }

    // We should also expect at least one internal file and method
    withClue("No internal file(s) detected.") {
      updated.file.nameNot(FileTraversal.UNKNOWN).size should be > 0
    }
    withClue("No internal method(s) detected.") {
      updated.method.isExternal(false).size should be > 0
    }

    // Assert no common oddities in the CPG, i.e, might result from non-idempotency of base passes
    updated.expression.count(_.in(EdgeTypes.AST).size != 1) shouldBe 0
    assertSingleEdgePairs(EdgeTypes.AST)
    assertSingleEdgePairs(EdgeTypes.CALL)
    assertSingleEdgePairs(EdgeTypes.REF)
    assertSingleEdgePairs(EdgeTypes.ARGUMENT)
    assertSingleEdgePairs(EdgeTypes.CONTAINS)
    assertSingleEdgePairs(EdgeTypes.SOURCE_FILE)
    assertSingleEdgePairs(EdgeTypes.EVAL_TYPE)
    assertSingleEdgePairs(EdgeTypes.INHERITS_FROM)
    // The below may not be present right now, but worth checking
    assertSingleEdgePairs(EdgeTypes.CFG)
    assertSingleEdgePairs(EdgeTypes.CDG)
    assertSingleEdgePairs(EdgeTypes.REACHING_DEF)

    // Determine all major structures are present and loosely equivalent
    withClue("Not all methods are equivalent in the updated graph.") {
      fromScratch.method.fullName.sorted.toList shouldBe updated.method.fullName.sorted.toList
    }
    withClue("Not all type declarations are equivalent in the updated graph.") {
      fromScratch.typeDecl.fullName.sorted.toList shouldBe updated.typeDecl.fullName.sorted.toList
    }
    withClue("Not all namespace blocks are equivalent in the updated graph.") {
      fromScratch.namespaceBlock.fullName.sorted.toList shouldBe updated.namespaceBlock.fullName.sorted.toList
    }
    withClue("Not all imports are equivalent in the updated graph.") {
      fromScratch.imports.importedEntity.sorted.toList shouldBe updated.imports.importedEntity.sorted.toList
    }

    // Determine basic AST equivalence
    withClue("Not all methods have the same type decl parents.") {
      def methodParentDump(cpg: Cpg): String =
        cpg.method.map(m => (m.fullName, m.typeDecl.map(_.fullName).sorted.l)).sorted.mkString("\n")

      methodParentDump(fromScratch) shouldBe methodParentDump(updated)
    }
    withClue("Not all namespace blocks have the same type decl children.") {
      def namespaceBlockChildrenDump(cpg: Cpg): String =
        cpg.namespaceBlock.map(n => (n.name, n.typeDecl.map(_.fullName).sorted.l)).sorted.mkString("\n")

      namespaceBlockChildrenDump(fromScratch) shouldBe namespaceBlockChildrenDump(updated)
    }
    withClue("Not all files have the same source-file children.") {
      def fileSourceChildren(cpg: Cpg): String =
        cpg.file.map(f => (f.name, f._sourceFileIn.cast[AstNode].map(x => (x.label, x.code)).sorted.l)).sorted.mkString("\n")

      fileSourceChildren(fromScratch) shouldBe fileSourceChildren(updated)
    }
  }

}
