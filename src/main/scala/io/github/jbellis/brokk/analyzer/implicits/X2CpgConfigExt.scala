package io.github.jbellis.brokk.analyzer.implicits

import io.github.jbellis.brokk.analyzer.builder.IncrementalCpgBuilder
import io.joern.x2cpg.X2CpgConfig
import io.shiftleft.codepropertygraph.generated.Cpg

import java.nio.file.Paths
import scala.util.{Try, Using}

object X2CpgConfigExt {

  extension [R <: X2CpgConfig[R]](config: R) {

    /**
     * @return opens the CPG associated with `config.outputPath` if one exists, or creates a new one at that location
     *         otherwise.
     */
    def open: Cpg = Cpg.withStorage(Paths.get(config.outputPath))

    /**
     * Builds or updates a CPG based on the given config. A new CPG is created or an existing one is loaded based on the
     * `outputPath` property of the config. The new or updated CPG will then be serialized to disk to ensure the
     * changes are fresh.
     *
     * @param builder the builder associated with the frontend specified by the instance of 'config'.
     * @return this configuration.
     */
    def build(using builder: IncrementalCpgBuilder[R]): Try[R] = withNewOrExistingCpg { cpg =>
      builder.build(cpg, config)
      config
    }

    /**
     * Alias for [[build]] where exceptions are thrown if one occurs.
     */
    def buildAndThrow(using builder: IncrementalCpgBuilder[R]): R = {
      build.failed.foreach(e => throw e)
      config
    }

    private def withNewOrExistingCpg(apply: Cpg => R): Try[R] = {
      val outputPath = Paths.get(config.outputPath)
      Try {
        Using.resource(Cpg.withStorage(outputPath)) { cpg =>
          Try(apply(cpg)).failed.foreach(e => throw e)
          config
        }
      }
    }

  }

}
