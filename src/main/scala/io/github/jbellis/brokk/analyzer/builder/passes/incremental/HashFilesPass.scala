package io.github.jbellis.brokk.analyzer.builder.passes.incremental

import io.github.jbellis.brokk.analyzer.implicits.CpgExt.*
import io.github.jbellis.brokk.analyzer.implicits.PathExt.*
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.generated.{Cpg, PropertyNames}
import io.shiftleft.passes.ForkJoinParallelCpgPass
import io.shiftleft.semanticcpg.language.*

import java.nio.file.Files
import scala.util.Try

class HashFilesPass(cpg: Cpg) extends ForkJoinParallelCpgPass[Seq[File]](cpg) {

  override def generateParts(): Array[Seq[File]] =
    cpg.file.grouped(HashFilesPass.BatchSize).toArray

  override def runOnPart(builder: DiffGraphBuilder, files: Seq[File]): Unit = {
    val root = cpg.projectRoot
    files.foreach { file =>
      // Attempt to turn the file name into a valid Path; skip on InvalidPathException
      val maybePath = Try(root.resolve(file.name)).toOption
      maybePath.foreach { absPath =>
        // There are some "external" placeholder file nodes which are not on disk
        if Files.isRegularFile(absPath) then {
          val fileHash = absPath.sha1
          if !file.hash.contains(fileHash) then builder.setNodeProperty(file, PropertyNames.HASH, fileHash)
        }
      }
    }
  }
}

private object HashFilesPass {
  private val BatchSize = 50
}
