package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class TypescriptAnalyzerTest {

    private static TreeSitterAnalyzerTest.TestProject project;
    private static TypescriptAnalyzer analyzer;

    // Helper to normalize line endings and strip leading/trailing whitespace from each line
    private static final Function<String, String> normalize =
        (String s) -> s.lines().map(String::strip).filter(line -> !line.isEmpty()).collect(Collectors.joining("\n"));

    @BeforeAll
    static void setUp(@TempDir Path tempDir) throws IOException {
        // Use a common TestProject setup method if available, or adapt TreeSitterAnalyzerTest.createTestProject
        Path testResourceRoot = Path.of("src/test/resources/testcode-ts");
        assertTrue(Files.exists(testResourceRoot) && Files.isDirectory(testResourceRoot),
                   "Test resource directory 'testcode-ts' must exist.");

        // For TypescriptAnalyzerTest, we'll point the TestProject root directly to testcode-ts
        project = TreeSitterAnalyzerTest.createTestProject("testcode-ts", Language.TYPESCRIPT);
        analyzer = new TypescriptAnalyzer(project); // Initialize with default excluded files (none)
        assertFalse(analyzer.isEmpty(), "Analyzer should have processed TypeScript files and not be empty.");
    }

    @Test
    void testHelloTsSkeletons() {
        ProjectFile helloTsFile = new ProjectFile(project.getRoot(), "Hello.ts");
        Map<CodeUnit, String> skeletons = analyzer.getSkeletons(helloTsFile);
        assertFalse(skeletons.isEmpty(), "Skeletons map for Hello.ts should not be empty.");

        CodeUnit greeterClass = CodeUnit.cls(helloTsFile, "", "Greeter");
        CodeUnit globalFunc = CodeUnit.fn(helloTsFile, "", "globalFunc");
        CodeUnit piConst = CodeUnit.field(helloTsFile, "", "_module_.PI");
        CodeUnit pointInterface = CodeUnit.cls(helloTsFile, "", "Point"); // Interfaces are CodeUnitType.CLASS
        CodeUnit colorEnum = CodeUnit.cls(helloTsFile, "", "Color");     // Enums are CodeUnitType.CLASS

        assertTrue(skeletons.containsKey(greeterClass), "Greeter class skeleton missing.");
        assertEquals(normalize.apply("""
            export class Greeter {
              greeting: string;
              constructor(message: string) { ... }
              greet(): string { ... }
            }"""), normalize.apply(skeletons.get(greeterClass)));

        assertTrue(skeletons.containsKey(globalFunc), "globalFunc skeleton missing.");
        assertEquals(normalize.apply("export function globalFunc(num: number): number { ... }"), normalize.apply(skeletons.get(globalFunc)));

        assertTrue(skeletons.containsKey(piConst), "PI const skeleton missing. Found: " + skeletons.keySet().stream().map(CodeUnit::fqName).collect(Collectors.joining(", ")));
        assertEquals(normalize.apply("export const PI: number = 3.14159"), normalize.apply(skeletons.get(piConst)));


        assertTrue(skeletons.containsKey(pointInterface), "Point interface skeleton missing.");
        assertEquals(normalize.apply("""
            export interface Point {
              x: number;
              y: number;
              label?: string;
              readonly originDistance?: number;
              move(dx: number, dy: number): void;
            }"""), normalize.apply(skeletons.get(pointInterface)));


        assertTrue(skeletons.containsKey(colorEnum), "Color enum skeleton missing.");
        assertEquals(normalize.apply("""
            export enum Color {
              Red,
              Green = 3,
              Blue
            }"""), normalize.apply(skeletons.get(colorEnum)));

        // Check getDeclarationsInFile
        Set<CodeUnit> declarations = analyzer.getDeclarationsInFile(helloTsFile);
        assertTrue(declarations.contains(greeterClass));
        assertTrue(declarations.contains(globalFunc));
        assertTrue(declarations.contains(piConst));
        assertTrue(declarations.contains(pointInterface));
        assertTrue(declarations.contains(colorEnum));
        // also members
        assertTrue(declarations.contains(CodeUnit.field(helloTsFile, "", "Greeter.greeting")));
        assertTrue(declarations.contains(CodeUnit.fn(helloTsFile, "", "Greeter.constructor")));
        assertTrue(declarations.contains(CodeUnit.fn(helloTsFile, "", "Greeter.greet")));
        assertTrue(declarations.contains(CodeUnit.field(helloTsFile, "", "Point.x")));
        assertTrue(declarations.contains(CodeUnit.fn(helloTsFile, "", "Point.move")));
        assertTrue(declarations.contains(CodeUnit.field(helloTsFile, "", "Color.Red")));


        // Test getSkeleton for individual items
        Optional<String> greetMethodSkeleton = analyzer.getSkeleton("Greeter.greet");
        assertTrue(greetMethodSkeleton.isPresent());
        // Note: getSkeleton for a method might only return its own line if it's not a top-level CU.
        // The full class skeleton is obtained by getSkeleton("Greeter").
        // The current reconstructFullSkeleton logic builds the full nested structure from the top-level CU.
        // If we call getSkeleton("Greeter.greet"), it should find the "Greeter" CU first, then reconstruct.
        // This means, if "Greeter.greet" itself is a CU in `signatures` (which it should be as a child),
        // then `reconstructFullSkeleton` called on `Greeter.greet` might only give its own signature.
        // Let's test `getSkeleton` on a top-level item:
        assertEquals(normalize.apply("export function globalFunc(num: number): number { ... }"),
                     normalize.apply(analyzer.getSkeleton("globalFunc").orElse("")));

    }

    @Test
    void testVarsTsSkeletons() {
        ProjectFile varsTsFile = new ProjectFile(project.getRoot(), "Vars.ts");
        Map<CodeUnit, String> skeletons = analyzer.getSkeletons(varsTsFile);

        CodeUnit maxUsers = CodeUnit.field(varsTsFile, "", "_module_.MAX_USERS");
        CodeUnit currentUser = CodeUnit.field(varsTsFile, "", "_module_.currentUser");
        CodeUnit config = CodeUnit.field(varsTsFile, "", "_module_.config");
        CodeUnit anArrowFunc = CodeUnit.fn(varsTsFile, "", "anArrowFunc"); // Arrow func assigned to const is a function CU
        CodeUnit legacyVar = CodeUnit.field(varsTsFile, "", "_module_.legacyVar");

        assertTrue(skeletons.containsKey(maxUsers));
        assertEquals(normalize.apply("export const MAX_USERS = 100"), normalize.apply(skeletons.get(maxUsers)));

        assertTrue(skeletons.containsKey(currentUser));
        assertEquals(normalize.apply("let currentUser: string = \"Alice\""), normalize.apply(skeletons.get(currentUser)));

        assertTrue(skeletons.containsKey(config));
        assertEquals(normalize.apply("const config = {"), normalize.apply(skeletons.get(config).lines().findFirst().orElse(""))); // obj literal, just check header

        assertTrue(skeletons.containsKey(anArrowFunc));
        assertEquals(normalize.apply("const anArrowFunc = (msg: string): void => { ... }"), normalize.apply(skeletons.get(anArrowFunc)));

        assertTrue(skeletons.containsKey(legacyVar));
        assertEquals(normalize.apply("export var legacyVar = \"legacy\""), normalize.apply(skeletons.get(legacyVar)));

        // A function declared inside Vars.ts but not exported
        CodeUnit localHelper = CodeUnit.fn(varsTsFile, "", "localHelper");
        assertTrue(skeletons.containsKey(localHelper));
        assertEquals(normalize.apply("function localHelper(): string { ... }"), normalize.apply(skeletons.get(localHelper)));
    }

    @Test
    void testModuleTsSkeletons() {
        ProjectFile moduleTsFile = new ProjectFile(project.getRoot(), "Module.ts"); // Assuming "" package for top level
        Map<CodeUnit, String> skeletons = analyzer.getSkeletons(moduleTsFile);

        CodeUnit myModule = CodeUnit.cls(moduleTsFile, "", "MyModule"); // Namespace is a class-like CU
        assertTrue(skeletons.containsKey(myModule), "MyModule namespace skeleton missing.");

        String expectedMyModuleSkeleton = """
            namespace MyModule {
              export class InnerClass {
                name: string = "Inner";
                constructor() { ... }
                doSomething(): void { ... }
              }
              export function innerFunc(): void { ... }
              export const innerConst: number = 42;
              export interface InnerInterface {
                id: number;
                describe(): string;
              }
              export enum InnerEnum {
                A,
                B
              }
              namespace NestedNamespace {
                export class DeeperClass {
                }
              }
            }""";
        assertEquals(normalize.apply(expectedMyModuleSkeleton), normalize.apply(skeletons.get(myModule)));

        CodeUnit anotherClass = CodeUnit.cls(moduleTsFile, "", "AnotherClass");
        assertTrue(skeletons.containsKey(anotherClass));
        assertEquals(normalize.apply("export class AnotherClass {\n}"), normalize.apply(skeletons.get(anotherClass)));
        
        CodeUnit topLevelArrow = CodeUnit.fn(moduleTsFile, "", "topLevelArrow");
        assertTrue(skeletons.containsKey(topLevelArrow));
        assertEquals(normalize.apply("export const topLevelArrow = (input: any): any => { ... }"), normalize.apply(skeletons.get(topLevelArrow)));

        // Check a nested item via getSkeleton
        Optional<String> innerClassSkel = analyzer.getSkeleton("MyModule$InnerClass");
        assertTrue(innerClassSkel.isPresent());
        // When getting skeleton for a nested CU, it should be part of the parent's reconstruction.
        // The current `getSkeleton` will reconstruct from the top-level parent of that CU.
        // So `getSkeleton("MyModule$InnerClass")` should effectively return the skeleton of `MyModule` because `InnerClass` is a child of `MyModule`.
        // This might be unintuitive if one expects only the InnerClass part.
        // Let's test this behavior:
        assertEquals(normalize.apply(expectedMyModuleSkeleton), normalize.apply(innerClassSkel.get()),
                     "getSkeleton for nested class should return the reconstructed parent skeleton.");

        Set<CodeUnit> declarations = analyzer.getDeclarationsInFile(moduleTsFile);
        assertTrue(declarations.contains(CodeUnit.cls(moduleTsFile, "MyModule", "NestedNamespace$DeeperClass")));
    }


    @Test
    void testAdvancedTsSkeletonsAndFeatures() {
        ProjectFile advancedTsFile = new ProjectFile(project.getRoot(), "Advanced.ts");
        Map<CodeUnit, String> skeletons = analyzer.getSkeletons(advancedTsFile);

        CodeUnit decoratedClass = CodeUnit.cls(advancedTsFile, "", "DecoratedClass");
        assertTrue(skeletons.containsKey(decoratedClass));
        assertEquals(normalize.apply("""
            @MyClassDecorator
            export class DecoratedClass<T> {
              @MyPropertyDecorator
              decoratedProperty: string = "initial";
              private _value: T;
              constructor(@MyParameterDecorator initialValue: T) { ... }
              @MyMethodDecorator
              genericMethod<U extends Point>(value: T, other: U): [T, U] { ... }
              get value(): T { ... }
              set value(val: T) { ... }
            }"""), normalize.apply(skeletons.get(decoratedClass)));

        CodeUnit genericInterface = CodeUnit.cls(advancedTsFile, "", "GenericInterface");
        assertTrue(skeletons.containsKey(genericInterface));
        assertEquals(normalize.apply("""
            export interface GenericInterface<T, U extends Point> {
              item: T;
              point: U;
              process(input: T): U;
              new (param: T): GenericInterface<T,U>;
            }"""), normalize.apply(skeletons.get(genericInterface)));

        CodeUnit abstractBase = CodeUnit.cls(advancedTsFile, "", "AbstractBase");
        assertTrue(skeletons.containsKey(abstractBase));
        assertEquals(normalize.apply("""
            export abstract class AbstractBase {
              abstract performAction(name: string): void;
              concreteMethod(): string { ... }
            }"""), normalize.apply(skeletons.get(abstractBase)));

        CodeUnit asyncArrow = CodeUnit.fn(advancedTsFile, "", "asyncArrowFunc");
        assertTrue(skeletons.containsKey(asyncArrow));
        assertEquals(normalize.apply("export const asyncArrowFunc = async (p: Promise<string>): Promise<number> => { ... }"), normalize.apply(skeletons.get(asyncArrow)));

        CodeUnit asyncNamed = CodeUnit.fn(advancedTsFile, "", "asyncNamedFunc");
        assertTrue(skeletons.containsKey(asyncNamed));
        assertEquals(normalize.apply("export async function asyncNamedFunc(param: number): Promise<void> { ... }"), normalize.apply(skeletons.get(asyncNamed)));

        CodeUnit fieldTest = CodeUnit.cls(advancedTsFile, "", "FieldTest");
        assertTrue(skeletons.containsKey(fieldTest));
         assertEquals(normalize.apply("""
            export class FieldTest {
              public name: string;
              private id: number = 0;
              protected status?: string;
              readonly creationDate: Date;
              static version: string = "1.0";
              #trulyPrivateField: string = "secret";
              constructor(name: string) { ... }
              public publicMethod() { ... }
              private privateMethod() { ... }
              protected protectedMethod() { ... }
              static staticMethod() { ... }
            }"""), normalize.apply(skeletons.get(fieldTest)));
    }
    
    @Test
    void testGetMethodSource() throws IOException {
        // From Hello.ts
        Optional<String> greetSource = analyzer.getMethodSource("Greeter.greet");
        assertTrue(greetSource.isPresent());
        assertEquals(normalize.apply("greet(): string {\n    return \"Hello, \" + this.greeting;\n}"), normalize.apply(greetSource.get()));

        Optional<String> constructorSource = analyzer.getMethodSource("Greeter.constructor");
        assertTrue(constructorSource.isPresent());
        assertEquals(normalize.apply("constructor(message: string) {\n    this.greeting = message;\n}"), normalize.apply(constructorSource.get()));
        
        // From Vars.ts (arrow function)
        Optional<String> arrowSource = analyzer.getMethodSource("anArrowFunc");
        assertTrue(arrowSource.isPresent());
        assertEquals(normalize.apply("const anArrowFunc = (msg: string): void => {\n    console.log(msg);\n};"), normalize.apply(arrowSource.get()));

        // From Advanced.ts (async named function)
        Optional<String> asyncNamedSource = analyzer.getMethodSource("asyncNamedFunc");
        assertTrue(asyncNamedSource.isPresent());
        assertEquals(normalize.apply("export async function asyncNamedFunc(param: number): Promise<void> {\n    await Promise.resolve();\n    console.log(param);\n}"), normalize.apply(asyncNamedSource.get()));
    }

    @Test
    void testGetSymbols() {
        ProjectFile helloTsFile = new ProjectFile(project.getRoot(), "Hello.ts");
        ProjectFile varsTsFile = new ProjectFile(project.getRoot(), "Vars.ts");

        CodeUnit greeterClass = CodeUnit.cls(helloTsFile, "", "Greeter");
        CodeUnit piConst = CodeUnit.field(varsTsFile, "", "_module_.PI"); // No, PI is in Hello.ts
        piConst = CodeUnit.field(helloTsFile, "", "_module_.PI");
        CodeUnit anArrowFunc = CodeUnit.fn(varsTsFile, "", "anArrowFunc");


        Set<CodeUnit> sources = Set.of(greeterClass, piConst, anArrowFunc);
        Set<String> symbols = analyzer.getSymbols(sources);

        // Expected:
        // From Greeter: "Greeter", "greeting", "constructor", "greet"
        // From PI: "PI"
        // From anArrowFunc: "anArrowFunc"
        Set<String> expectedSymbols = Set.of(
                "Greeter", "greeting", "constructor", "greet",
                "PI",
                "anArrowFunc"
        );
        assertEquals(expectedSymbols, symbols);

        // Test with interface
        CodeUnit pointInterface = CodeUnit.cls(helloTsFile, "", "Point");
        Set<String> interfaceSymbols = analyzer.getSymbols(Set.of(pointInterface));
        assertEquals(Set.of("Point", "x", "y", "label", "originDistance", "move"), interfaceSymbols);
    }

    @Test
    void testGetClassSource() throws IOException {
        // Test with Greeter class from Hello.ts
        String greeterSource = analyzer.getClassSource("Greeter");
        assertNotNull(greeterSource);
        assertTrue(greeterSource.startsWith("export class Greeter"));
        assertTrue(greeterSource.contains("greeting: string;"));
        assertTrue(greeterSource.contains("greet(): string {"));
        assertTrue(greeterSource.endsWith("}"));

        // Test with Point interface from Hello.ts
        String pointSource = analyzer.getClassSource("Point");
        assertNotNull(pointSource);
        assertTrue(pointSource.startsWith("export interface Point"));
        assertTrue(pointSource.contains("x: number;"));
        assertTrue(pointSource.contains("move(dx: number, dy: number): void;"));
        assertTrue(pointSource.endsWith("}"));
    }
}
