/*
 * Copyright 2025 The Joern Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Modifications copyright 2025 Brokk, Inc. and made available under the GPLv3.
 *
 * The original file can be found at https://github.com/joernio/joern/blob/3e923e15368e64648e6c5693ac014a2cac83990a/joern-cli/frontends/javasrc2cpg/src/main/scala/io/joern/javasrc2cpg/scope/JavaScopeElement.scala
 */
package io.joern.javasrc2cpg.scope

import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.expr.TypePatternExpr
import io.joern.javasrc2cpg.astcreation.ExpectedType
import io.joern.javasrc2cpg.scope.JavaScopeElement.*
import io.joern.javasrc2cpg.scope.Scope.*
import io.joern.javasrc2cpg.scope.TypeType.{ReferenceTypeType, TypeVariableType}
import io.joern.javasrc2cpg.util.{BindingTableEntry, NameConstants}
import io.joern.x2cpg.utils.IntervalKeyPool
import io.joern.x2cpg.{Ast, ValidationMode}
import io.shiftleft.codepropertygraph.generated.nodes.*

import java.util
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

enum TypeType:
  case ReferenceTypeType, TypeVariableType

trait JavaScopeElement(disableTypeFallback: Boolean) {
  private val variables                        = mutable.Map[String, ScopeVariable]()
  private val types                            = mutable.Map[String, ScopeType]()
  private var wildcardImports: WildcardImports = NoWildcard
  // TODO: This is almost a duplicate of types, but not quite since this stores type variables and local types
  //  with original names. See if there's a way to combine them
  private val declaredTypeTypes = mutable.Map[String, TypeType]()

  def isStatic: Boolean

  private[JavaScopeElement] def addVariableToScope(variable: ScopeVariable): Unit = {
    variables.put(variable.name, variable)
  }

  def addDeclaredTypeType(typeSimpleName: String, isTypeVariable: Boolean): Unit = {
    val typeType =
      if (isTypeVariable)
        TypeVariableType
      else {
        ReferenceTypeType
      }

    declaredTypeTypes.put(typeSimpleName, typeType)
  }

  def getDeclaredTypeType(typeSimpleName: String): Option[TypeType] = {
    declaredTypeTypes.get(typeSimpleName)
  }

  def lookupVariable(name: String): VariableLookupResult = {
    variables.get(name).map(SimpleVariable(_)).getOrElse(NotInScope)
  }

  def addTypeToScope(name: String, scopeType: ScopeType): Unit = {
    types.put(name, scopeType)
  }

  def lookupType(name: String, includeWildcards: Boolean): Option[ScopeType] = {
    types.get(name) match {
      case None if includeWildcards && !disableTypeFallback => getNameWithWildcardPrefix(name)
      case result                                           => result
    }
  }

  def getNameWithWildcardPrefix(name: String): Option[ScopeType] = {
    wildcardImports match {
      case SingleWildcard(prefix) => Some(ScopeTopLevelType(s"$prefix.$name", name))

      case _ => None
    }
  }

  def addWildcardImport(prefix: String): Unit = {
    wildcardImports match {
      case NoWildcard => wildcardImports = SingleWildcard(prefix)

      case SingleWildcard(_) => wildcardImports = MultipleWildcards

      case MultipleWildcards => // Already MultipleWildcards, so change nothing
    }
  }

  def getVariables(): List[ScopeVariable] = variables.values.toList
}

case class PatternVariableInfo(
  typePatternExpr: TypePatternExpr,
  typeVariableLocal: NewLocal,
  typeVariableInitializer: Ast,
  localAddedToAst: Boolean = false,
  initializerAddedToAst: Boolean = false,
  index: Int
)

object JavaScopeElement {
  sealed trait WildcardImports
  case object NoWildcard                    extends WildcardImports
  case class SingleWildcard(prefix: String) extends WildcardImports
  case object MultipleWildcards             extends WildcardImports

  trait AnonymousClassCounter {
    private val anonymousClassKeyPool = IntervalKeyPool(1, Long.MaxValue)

    def getNextAnonymousClassIndex(): Long = anonymousClassKeyPool.next
  }

  class NamespaceScope(val namespace: NewNamespaceBlock)(implicit disableTypeFallback: Boolean)
      extends JavaScopeElement(disableTypeFallback)
      with TypeDeclContainer {
    val isStatic = false
  }

  class BlockScope(implicit disableTypeFallback: Boolean) extends JavaScopeElement(disableTypeFallback) {
    private val hoistedPatternLocals: mutable.Set[NewLocal] = mutable.Set()

    val isStatic = false

    def addLocal(local: NewLocal, originalName: String): Unit = {
      addVariableToScope(ScopeLocal(local, originalName))
    }

    def addHoistedPatternLocal(local: NewLocal): Unit = {
      hoistedPatternLocals.addOne(local)
    }

    def getHoistedPatternLocals: Set[NewLocal] = {
      hoistedPatternLocals.toSet
    }
  }

  class MethodScope(val method: NewMethod, val returnType: ExpectedType, override val isStatic: Boolean)(implicit
    val withSchemaValidation: ValidationMode,
    disableTypeFallback: Boolean
  ) extends JavaScopeElement(disableTypeFallback)
      with AnonymousClassCounter {

    private val patternLocalsToAddToCpg: mutable.ListBuffer[NewLocal] = mutable.ListBuffer()
    private val patternLocalMap       = new util.IdentityHashMap[TypePatternExpr, NewLocal]().asScala
    private val mangledNameIdxKeyPool = IntervalKeyPool(0, Long.MaxValue)

    def addParameter(parameter: NewMethodParameterIn, genericSignature: String): Unit = {
      addVariableToScope(ScopeParameter(parameter, genericSignature))
    }

    def registerPatternLocal(typePatternExpr: TypePatternExpr, local: NewLocal): Unit = {
      patternLocalMap.put(typePatternExpr, local)
    }

    def registerLocalToAddToCpg(local: NewLocal): Unit = {
      patternLocalsToAddToCpg.addOne(local)
    }

    def getAndClearUnaddedPatternLocals(): List[NewLocal] = {
      val ret = patternLocalsToAddToCpg.toList
      patternLocalsToAddToCpg.clear()
      ret
    }

    def getLocalForPattern(typePatternExpr: TypePatternExpr): Option[NewLocal] = {
      patternLocalMap.get(typePatternExpr)
    }

    def mangleLocalName(originalName: String): String = {
      s"$originalName$$${mangledNameIdxKeyPool.next}"
    }
  }

  class FieldDeclScope(override val isStatic: Boolean, val name: String)(implicit disableTypeFallback: Boolean)
      extends JavaScopeElement(disableTypeFallback)

  class TypeDeclScope(
    val typeDecl: NewTypeDecl,
    override val isStatic: Boolean,
    private[scope] val capturedVariables: Map[String, CapturedVariable],
    outerClassType: Option[String],
    outerClassGenericSignature: Option[String],
    val declaredMethodNames: Set[String],
    val recordParameters: List[Parameter]
  )(implicit disableTypeFallback: Boolean)
      extends JavaScopeElement(disableTypeFallback)
      with TypeDeclContainer
      with AnonymousClassCounter {
    private val bindingTableEntries            = mutable.ListBuffer[BindingTableEntry]()
    private val usedCaptureParams              = mutable.Set[ScopeVariable]()
    private[scope] val initsToComplete         = mutable.ListBuffer[PartialInit]()
    private[scope] val capturesForTypesInScope = mutable.Map[String, List[ScopeVariable]]()

    override def lookupVariable(name: String): VariableLookupResult = {
      super.lookupVariable(name) match {
        case NotInScope => capturedVariables.get(name).getOrElse(NotInScope)

        case result => result
      }
    }

    def addBindingTableEntry(bindingTableEntry: BindingTableEntry): Unit = {
      bindingTableEntries.addOne(bindingTableEntry)
    }

    def getBindingTableEntries: List[BindingTableEntry] = bindingTableEntries.toList

    def addMember(member: NewMember, isStatic: Boolean): Unit = {
      addVariableToScope(ScopeMember(member, isStatic))
    }

    def registerCaptureUse(variable: ScopeVariable): Unit = {
      usedCaptureParams.addOne(variable)
    }

    def registerInitToComplete(partialInit: PartialInit): Unit = {
      initsToComplete.append(partialInit)
    }

    def registerCapturesForType(typeFullName: String, captures: List[ScopeVariable]): Unit = {
      capturesForTypesInScope.put(typeFullName, captures)
    }

    def getUsedCaptures(): List[ScopeVariable] = {
      val outerScope = outerClassType.map(typ =>
        val localNode = NewLocal().name(NameConstants.OuterClass).typeFullName(typ).code(NameConstants.OuterClass)
        outerClassGenericSignature.foreach(localNode.genericSignature(_))
        ScopeLocal(localNode, NameConstants.OuterClass)
      )

      val sortedUsedCaptures = usedCaptureParams.toList.sortBy(_.name)
      val usedLocals         = sortedUsedCaptures.collect { case local: ScopeLocal => local }
      val usedParameters     = sortedUsedCaptures.collect { case param: ScopeParameter => param }
      // Don't include members since they're accessed through "outerClass"
      outerScope.toList ++ usedLocals ++ usedParameters
    }

    private[scope] def getUsedCapturesForType(typeFullName: String): List[ScopeVariable] = {
      capturesForTypesInScope.getOrElse(typeFullName, Nil)
    }
  }

  case class PartialInit(
    typeFullName: String,
    callAst: Ast,
    receiverAst: Ast,
    argsAsts: List[Ast],
    outerClassAst: Option[Ast]
  )

  extension (typeDeclScope: Option[TypeDeclScope]) {
    def fullName: Option[String]               = typeDeclScope.map(_.typeDecl.fullName)
    def name: Option[String]                   = typeDeclScope.map(_.typeDecl.name)
    def isInterface: Boolean                   = typeDeclScope.exists(_.typeDecl.code.contains("interface "))
    def getUsedCaptures(): List[ScopeVariable] = typeDeclScope.map(_.getUsedCaptures()).getOrElse(Nil)
    def getCapturesForType(typeFullName: String): List[ScopeVariable] =
      typeDeclScope.map(_.getUsedCapturesForType(typeFullName)).getOrElse(Nil)
    def getInitsToComplete: List[PartialInit] = typeDeclScope.map(_.initsToComplete.toList).getOrElse(Nil)
  }

  extension (methodScope: Option[MethodScope]) {
    def getMethodFullName: String = methodScope.get.method.fullName
  }
}
