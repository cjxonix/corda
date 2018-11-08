package net.corda.tools.serialization

import net.corda.cliutils.CordaCliWrapper
import net.corda.cliutils.start
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.SerializationContext
import net.corda.core.serialization.SerializationFactory
import net.corda.core.serialization.internal.SerializationEnvironment
import net.corda.core.serialization.internal._contextSerializationEnv
import net.corda.core.serialization.serialize
import net.corda.serialization.internal.AMQP_P2P_CONTEXT
import net.corda.serialization.internal.AMQP_STORAGE_CONTEXT
import net.corda.serialization.internal.CordaSerializationMagic
import net.corda.serialization.internal.SerializationFactoryImpl
import net.corda.serialization.internal.amqp.*
import org.apache.qpid.proton.amqp.Symbol
import picocli.CommandLine
import java.io.File
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.*

fun main(args: Array<String>) = GenerateSerializationLibs().start(args)

@CordaSerializable data class Village(val name: String)
@CordaSerializable data class City(val name: String)
@CordaSerializable data class Person<T : Any>(val name: String, val age: Int, val bornLived: Pair<T, T>)
@CordaSerializable data class Family(val parents: Pair<Person<City>, Person<Village>>, val lastName: String, val livingIn: City)

/**
 * Generates C++ source code that deserialises types, based on types discovered using classpath scanning.
 */
class GenerateSerializationLibs : CordaCliWrapper("generate-serialization-libs", "Generate source code for reading serialised messages in non-JVM languages") {
    @CommandLine.Parameters(index = "0", paramLabel = "OUTPUT", description = ["Path to where the output files are generated"], defaultValue = "out")
    lateinit var outputDirectory: String

    private val Type.cppName: String get() {
        return this.typeName.replace(".", "::")
    }

    override fun runProgram(): Int {
        initSerialization()

        makeTestData()

        val outPath = Paths.get(outputDirectory)
        Files.createDirectories(outPath)

        val allHeaders = mutableListOf<String>()
        generateClassesFor(listOf(Family::class.java, City::class.java, Village::class.java)) { path, content ->
            val filePath = outPath.resolve(path)
            Files.createDirectories(filePath.parent)
            Files.write(filePath, content.toByteArray())
            println("Generated $filePath")
            allHeaders += path.toString()
        }

        val uberHeader = allHeaders.map { "#include \"$it\"" }
        val uberHeaderPath = outPath.resolve("all-messages.h")
        Files.write(uberHeaderPath, uberHeader)
        println("Generated $uberHeaderPath")
        return 0
    }

    private fun makeTestData() {
        val manchester = City("Manchester")
        val zurich = City("Zurich")
        val hannover = Village("Hannover")
        val lausanne = Village("Lausanne")
        val geneva = City("Geneva")
        val family = Family(
                Pair(
                        Person("Mike", 34, manchester to zurich),
                        Person("Angie", 26, hannover to lausanne)),
                "Hearn",
                geneva
        )
        File("/tmp/buf").writeBytes(family.serialize().bytes)
    }

    private val Type.baseClass: Class<*> get() = ((this as? ParameterizedType)?.rawType as? Class<*> ?: this as Class<*>)
    private val Type.eraseGenerics: String get() = baseClass.toGenericString()

    private data class GenResult(val code: String?, val dependencies: Set<Type>, val needsSpecialisationFor: Symbol?)

    /**
     * Invokes the given callback with the file path and the file contents that should be created.
     */
    private fun generateClassesFor(roots: List<Type>, block: (Path, String) -> Unit) {
        // Generate a chunk of code for all the given types and all the resulting dependencies of those types.
        // This code is made complex by the desire to support generics. We will convert Java generic classes to
        // C++ templated classes, and use specialisation to allow the same class to have many concrete descriptors
        // at decode time.
        //
        // buf will contain the class definitions, and at the end a pile of specialised descriptor functions.
        val buf = StringBuffer()
        // The factory lets us look up serializers, which gives us the fingerprints we will use to check the format
        // of the data we're decoding matches the generated code. This version of C++ support doesn't do evolution.
        val serializerFactory: SerializerFactory = Scheme.getSerializerFactory(SerializationFactory.defaultFactory.defaultContext)
        // We don't want to generate the same code twice, so we keep track of the classes we already made here.
        // This set contains type names with erased type parameters in C++ form, e.g. kotlin::Pair<A, B>, not resolved
        // type parameters.
        val seenSoFar = mutableSetOf<String>()
        // The work queue keeps track of types we haven't visited yet.
        val workQ = LinkedList<Type>()
        workQ += roots
        // Map of base type/class name (without type params) to generated code.
        val classSources = HashMap<String, String>()
        // Map of base type/class name (without type params) to a list of pre-declarations of classes it depends on.
        //
        // We pre-declare classes rather than do the obvious thing of importing headers, because that approach led to
        // circular dependencies between headers due to the specializations.
        val classPredeclarations = HashMap<String, MutableSet<Type>>()
        // Map of base type/class name (without type params) to a set of specialisations for that class.
        //
        // We face a simple problem: we'd like to generate a single class with the obvious name for generic types
        // like Pair, but the concrete serialised structures have a descriptor symbol (e.g. "net.corda:AMNq64uhVP8WpSl2MBKq7A==")
        // that depends on the values of the type variables. That is, kotlin::Pair<Foo, Bar> has a different descriptor
        // to kotlin::Pair<Biz, Boz>. Therefore the generated code calls a function to find out what its descriptor
        // should be, and we provide more specific versions of this function outside the class body itself, at the end
        // of the code block. When specialising the class, the compiler will pick the specialised function to match
        // and the code will look for the right descriptor. We keep track of the specialisations we need here.
        val descriptorSpecializations = HashMap<String, MutableSet<String>>()
        while (workQ.isNotEmpty()) {
            val type = workQ.pop()
            try {
                check(!type.baseClass.isArray)
                val baseName: String = type.baseClass.name
                val (code, dependencies, needsSpecialisationFor) = generateClassFor(type, serializerFactory, seenSoFar)

                // We have to pre-declare any type that appears anywhere in any field type, including in nested generics.
                val predeclarationsNeeded: MutableSet<Type> = dependencies
                        .flatMap { it.allMentionedClasses }
                        .map { it.baseClass }
                        .toMutableSet()

                if (needsSpecialisationFor != null) {
                    val specialization = """template<> const std::string ${type.cppName}::descriptor() { return "$needsSpecialisationFor"; }"""
                    descriptorSpecializations.getOrPut(baseName) { LinkedHashSet() } += specialization
                    predeclarationsNeeded += type.allMentionedClasses
                }

                // generateClassFor may not have actually generated any class code, if we already did so (it's already
                // in seenSoFar). We have to let generateClassFor run at least a little bit because even if we generated
                // the class already, this particular template instantiation of it might have changed the types of its
                // fields and that, in turn, may require us to generate more descriptor specializations. So we have to
                // explore the dependency graph taking into account resolved type variables, even though the final
                // emitted code is a template.
                if (code != null)
                    classSources[baseName] = code

                if (predeclarationsNeeded.isNotEmpty())
                    classPredeclarations.getOrPut(baseName) { LinkedHashSet() }.addAll(predeclarationsNeeded)

                workQ += dependencies
                seenSoFar += type.eraseGenerics
            } catch (e: AMQPNotSerializableException) {
                buf.appendln("// Skipping $type due to inability to process it: ${e.message}")
            }
        }

        for ((baseName, classSource) in classSources) {
            val path = Paths.get(baseName.replace('.', File.separatorChar) + ".h")
            val guardName = baseName.toUpperCase().replace('.', '_') + "_H"
            val predeclarations = classPredeclarations[baseName]?.let { formatPredeclarations(it, baseName) } ?: ""
            val specializations = descriptorSpecializations[baseName]?.joinToString(System.lineSeparator()) ?: ""
            block(path, """
                |////////////////////////////////////////////////////////////////////////////////////////////////////////
                |// Auto-generated code. Do not edit.
                |
                |#ifndef $guardName
                |#define $guardName
                |
                |#include "corda.h"
                |
                |// Pre-declarations to speed up processing and avoid circular header dependencies.
                |$predeclarations
                |// End of pre-declarations.
                |
                |$classSource
                |
                |// Template specializations of the descriptor() method.
                |$specializations
                |// End specializations.
                |
                |#endif
            """.trimMargin())
        }
    }

    private fun formatPredeclarations(predeclarations: MutableSet<Type>, baseName: String): String {
        val predeclaration = StringBuffer()
        // Group them by package to reduce the amount of repetitive namespace nesting we need.
        val groupedPredeclarations: Map<Package, List<Type>> = predeclarations.groupBy { it.baseClass.`package` }
        for ((pkg, types) in groupedPredeclarations) {
            // Don't need to pre-declare ourselves.
            if (types.singleOrNull()?.baseClass?.name == baseName) continue
            val (openings, closings) = namespaceBoilerplate("${pkg.name}.DummyClass".split('.'))
            predeclaration.appendln(openings)
            for (needed in types) {
                val params = needed.baseClass.typeParameters
                predeclaration.appendln("${templateHeader(params)}class ${needed.baseClass.simpleName};")
            }
            predeclaration.append(closings)
        }
        return predeclaration.toString()
    }

    // Foo<Bar, Baz<Boz>> -> [Foo, Bar, Baz, Boz]
    private val Type.allMentionedClasses: Set<Class<*>> get() {
        val result = HashSet<Class<*>>()

        fun recurse(t: Type) {
            result += t.baseClass
            if (t !is ParameterizedType) return
            for (argument in t.actualTypeArguments) {
                recurse(argument)
            }
        }

        recurse(this)
        return result
    }

    private fun generateClassFor(type: Type, serializerFactory: SerializerFactory, seenSoFar: Set<String>): GenResult {
        // Get the serializer created by the serialization engine, and map it to C++.
        val amqpSerializer: AMQPSerializer<Any> = serializerFactory.get(type)
        if (amqpSerializer !is ObjectSerializer) {
            // Some serialisers are special and need to be hand coded.
            return GenResult("// TODO: Need to write code for custom serializer ${amqpSerializer.type} / ${amqpSerializer.typeDescriptor}", emptySet(), null)
        }

        // Calculate the body of the class where field are declared and initialised in the constructor.
        val descriptorSymbol = amqpSerializer.typeDescriptor
        val fieldDeclarations = mutableListOf<String>()
        val fieldInitializations = mutableListOf<String>()
        val dependencies = mutableSetOf<Type>()
        for (accessor in amqpSerializer.propertySerializers.serializationOrder) {
            val javaName = accessor.serializer.name
            val name = javaToCPPName(javaName)
            val declType = when (accessor.serializer.type) {
                // AMQP type to C++ type. See SerializerFactory.primitiveTypeNames and https://qpid.apache.org/releases/qpid-proton-0.26.0/proton/cpp/api/types_page.html
                "char" -> "char"
                "boolean" -> "bool"
                "byte" -> "int8_t"
                "ubyte" -> "uint8_t"
                "short" -> "int16_t"
                "ushort" -> "uint16_t"
                "int" -> "int32_t"
                "uint" -> "uint32_t"
                "long" -> "int64_t"
                "ulong" -> "uint64_t"
                "float" -> "float"
                "double" -> "double"
                "decimal32" -> "proton::decimal32"
                "decimal64" -> "proton::decimal64"
                "decimal128" -> "proton::decimal128"
                "timestamp" -> "proton::timestamp"
                "uuid" -> "proton::uuid"
                "binary" -> "proton::binary"
                "string" -> "std::string"
                "symbol" -> "proton::symbol"

                else -> {
                    val resolved: Type = accessor.serializer.resolvedType
                    val genericReturnType = (accessor.serializer.propertyReader as PublicPropertyReader).genericReturnType
                    // The resolved type may be a Class directly, or it may be a generic type, in which case we need to
                    // erase the generics to generate the code.
                    dependencies += resolved
                    "std::unique_ptr<${genericReturnType.cppName}>"
                }
            }

            fieldDeclarations += "$declType $name;"
            fieldInitializations += "::corda::Parser::read_to(decoder, $name);"
        }

        // We have fully specified generics here, as used in the parameter types e.g. kotlin.Pair<Person, Person>
        // but we want to generate generic C++, so we have to put the type variables back
        val typeParameters = ((type as? ParameterizedType)?.rawType as? Class<*>)?.typeParameters ?: emptyArray()
        val isGeneric = typeParameters.isNotEmpty()

        // Bail out early without generating code if we already did this class. We had to scan it anyway to discover
        // if there were any new dependencies as a result of the template substitution.
        if (type.eraseGenerics in seenSoFar)
            return GenResult(null, dependencies, if (isGeneric) descriptorSymbol else null)

        // Calculate the right namespace{} blocks.
        val nameComponents = type.cppName.substringBefore('<').split("::")
        val (namespaceOpenings, namespaceClosings) = namespaceBoilerplate(nameComponents)
        val undecoratedName = nameComponents.last()

        val descriptorFunction = if (isGeneric) {
            "const std::string descriptor();"
        } else {
            "const std::string descriptor() { return \"$descriptorSymbol\"; }"
        }

        return GenResult("""
                    |$namespaceOpenings
                    |
                    |${templateHeader(typeParameters)}class $undecoratedName {
                    |public:
                    |    ${fieldDeclarations.joinToString(System.lineSeparator() + (" ".repeat(4)))}
                    |
                    |    explicit $undecoratedName(proton::codec::decoder &decoder) {
                    |        ::corda::DescriptorGuard d(decoder, descriptor(), ${fieldDeclarations.size});
                    |        ${fieldInitializations.joinToString(System.lineSeparator() + (" ".repeat(8)))}
                    |    }
                    |
                    |    $descriptorFunction
                    |};
                    |
                    |$namespaceClosings
                """.trimMargin(), dependencies, if (isGeneric) descriptorSymbol else null)
    }

    private fun javaToCPPName(javaName: String): String {
        val buf = StringBuffer()
        for (c in javaName) {
            if (c.isLowerCase()) {
                buf.append(c)
            } else {
                buf.append('_')
                buf.append(c.toLowerCase())
            }
        }
        return buf.toString()
    }

    private fun templateHeader(typeParameters: Array<out TypeVariable<out Class<out Any>>>) =
            if (typeParameters.isEmpty()) "" else "template <" + typeParameters.joinToString { "class $it" } + "> "

    private fun namespaceBoilerplate(nameComponents: List<String>): Pair<String, String> {
        val namespaceComponents: List<String> = nameComponents.dropLast(1)
        val namespaceOpenings = namespaceComponents.joinToString(System.lineSeparator()) { "namespace $it {" }
        val namespaceClosings = Array(namespaceComponents.size) { "}" }.joinToString(System.lineSeparator())
        return Pair(namespaceOpenings, namespaceClosings)
    }

    private fun initSerialization() {
        val factory = SerializationFactoryImpl()
        factory.registerScheme(Scheme)
        _contextSerializationEnv.set(SerializationEnvironment.with(
                factory,
                p2pContext = AMQP_P2P_CONTEXT.withLenientCarpenter(),
                storageContext = AMQP_STORAGE_CONTEXT.withLenientCarpenter()
        ))
        // Hack: Just force the serialization engine to fully initialize by serializing something.
        Scheme.serialize(Instant.now(), factory.defaultContext)
    }

    private object Scheme : AbstractAMQPSerializationScheme(emptyList()) {
        override fun canDeserializeVersion(magic: CordaSerializationMagic, target: SerializationContext.UseCase): Boolean {
            return magic == amqpMagic
        }

        override fun rpcClientSerializerFactory(context: SerializationContext) = throw UnsupportedOperationException()
        override fun rpcServerSerializerFactory(context: SerializationContext) = throw UnsupportedOperationException()
    }
}